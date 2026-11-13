package app.orchard.tv.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A ring of seven pictures on disk, dealt one at a time (owner, 2026-11-13).
 *
 * While a source is in use the pool is filled to [CAPACITY] in the background, and the next
 * picture is decoded, shaded and frosted before anyone looks at it — showing it then costs no
 * network and no decode. After [REPLACE_AFTER] shows, one new picture is fetched and the oldest
 * entry is dropped in its favour. Pictures already shown stay in the ring and come round again,
 * so the pool never runs dry; only one baked copy is held in memory at a time.
 *
 * [fetch] writes one new file named "<id>.jpg" into the pool's directory and describes it. It is
 * called on an IO thread with the ids already in the pool, so it can avoid duplicates. [bake]
 * turns that file into the shaded home picture and its frosted copy. [onChanged] and [onStaged]
 * run on the main thread, for the caller to rebuild its lists or show a first picture.
 */
internal class RollingPool(
    private val dir: File,
    private val prefs: SharedPreferences,
    private val name: String,
    private val scope: CoroutineScope,
    private val fetch: suspend (existing: Set<String>) -> Entry?,
    private val bake: (File) -> Pair<Bitmap, Bitmap>?,
    private val onChanged: () -> Unit,
    private val onStaged: () -> Unit,
) {
    companion object {
        const val CAPACITY = 7
        const val REPLACE_AFTER = 6
    }

    data class Entry(val id: String, val title: String = "", val caption: String = "") {
        fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("caption", caption)
    }

    class Staged(val entry: Entry, val file: File, val home: Bitmap, val frost: Bitmap)

    private val gate = Any()
    private val order = ArrayList<Entry>()        // oldest first
    private var cursor = 0                        // the next entry to deal
    private var used = 0                          // shows since the last new picture
    @Volatile private var working = false         // a fill or a replacement is running
    @Volatile private var baking = false
    @Volatile private var generation = 0
    @Volatile var staged: Staged? = null
    @Volatile var shown: String? = null           // the id on screen now
    @Volatile var pendingShow = false             // first install: show as soon as one is ready

    init {
        dir.mkdirs()
        runCatching {
            val s = prefs.getString("pool_$name", null) ?: return@runCatching
            val o = JSONObject(s)
            val arr = o.optJSONArray("order") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val e = Entry(j.optString("id"), j.optString("title"), j.optString("caption"))
                if (e.id.isNotEmpty() && file(e.id).exists()) order.add(e)
            }
            cursor = o.optInt("cursor", 0)
            used = o.optInt("used", 0)
            shown = o.optString("shown").ifEmpty { null }
        }
        while (order.size > CAPACITY) {
            val dropped = order.removeAt(0)
            file(dropped.id).delete()
        }
        // A fetch cut short may have left a file that never made it into the order.
        dir.listFiles { f -> f.extension == "jpg" && order.none { it.id == f.nameWithoutExtension } }
            ?.forEach { it.delete() }
        save()
    }

    private fun file(id: String) = File(dir, "$id.jpg")
    fun fileFor(id: String): File = file(id)
    fun entries(): List<Entry> = synchronized(gate) { order.toList() }
    fun ids(): Set<String> = synchronized(gate) { order.mapTo(HashSet()) { it.id } }
    fun size(): Int = synchronized(gate) { order.size }
    fun current(): Entry? = synchronized(gate) { if (order.isEmpty()) null else order[cursor % order.size] }
    fun shownEntry(): Entry? = synchronized(gate) { order.firstOrNull { it.id == shown } }
    fun contains(id: String): Boolean = synchronized(gate) { order.any { it.id == id } }

    private fun save() = prefs.edit().putString(
        "pool_$name",
        JSONObject()
            .put("cursor", cursor)
            .put("used", used)
            .put("shown", shown ?: "")
            .put("order", JSONArray().apply { order.forEach { put(it.json()) } })
            .toString(),
    ).apply()

    /** Fills the pool and deals the next picture while nobody is waiting for it. */
    fun ensure() {
        if (working) return
        if (size() < CAPACITY) fill() else if (staged == null) deal()
    }

    /** Shows the dealt picture and keeps the ring turning; null when the next one is not ready. */
    fun show(): Staged? {
        val ready = staged ?: return null
        staged = null
        pendingShow = false
        val replace = synchronized(gate) {
            shown = ready.entry.id
            if (order.isNotEmpty()) cursor = (cursor + 1) % order.size
            used++
            save()
            used >= REPLACE_AFTER
        }
        if (replace) replaceOldest() else deal()
        return ready
    }

    /** Count a show that draws straight from the pool (the photo screensaver), without dealing. */
    fun noteUse() {
        val replace = synchronized(gate) {
            if (order.isEmpty()) return
            cursor = (cursor + 1) % order.size
            used++
            save()
            used >= REPLACE_AFTER
        }
        if (replace) replaceOldest()
    }

    private fun fill() {
        working = true
        val gen = generation
        scope.launch(Dispatchers.IO) {
            var first = true
            var added = false
            while (size() < CAPACITY && generation == gen) {
                val e = fetch(ids()) ?: break
                synchronized(gate) { order.add(e); save() }
                added = true
                // The first picture must be on screen without waiting for the other six: deal it
                // at once, then carry on filling the ring in the background.
                if (first) {
                    first = false
                    withContext(Dispatchers.Main) {
                        if (generation == gen) { onChanged(); deal() }
                    }
                }
            }
            working = false
            if (generation == gen) withContext(Dispatchers.Main) {
                if (added) onChanged()
                ensure()
            }
        }
    }

    private fun replaceOldest() {
        // A fill is already running; the next show will ask again and the ring stays at seven.
        if (working) return
        working = true
        val gen = generation
        scope.launch(Dispatchers.IO) {
            val e = fetch(ids())
            if (e != null && generation == gen) {
                val old = synchronized(gate) {
                    val dropped = if (order.size >= CAPACITY) order.removeAt(0) else null
                    order.add(e)
                    used = 0
                    cursor = if (order.isEmpty()) 0 else (cursor - 1 + order.size) % order.size
                    save()
                    dropped
                }
                old?.let { if (it.id != e.id) file(it.id).delete() }
                withContext(Dispatchers.Main) { if (generation == gen) onChanged() }
            } else {
                // The host was unreachable: try again after the next six shows, not forever now.
                synchronized(gate) { used = REPLACE_AFTER - 1; save() }
            }
            working = false
            if (generation == gen) withContext(Dispatchers.Main) { ensure() }
        }
    }

    private fun deal() {
        if (baking) return
        val e = current() ?: return
        val f = file(e.id)
        if (!f.exists()) return
        baking = true
        val gen = generation
        scope.launch(Dispatchers.IO) {
            val baked = runCatching { bake(f) }.getOrNull()
            baking = false
            if (baked != null && generation == gen && contains(e.id)) {
                staged = Staged(e, f, baked.first, baked.second)
                withContext(Dispatchers.Main) { if (generation == gen) onStaged() }
            }
        }
    }

    /** The address (or the market) changed: nothing from the old one may be dealt again. */
    fun clear() {
        generation++
        working = false
        baking = false
        staged = null
        pendingShow = true
        synchronized(gate) {
            order.forEach { file(it.id).delete() }
            order.clear()
            cursor = 0
            used = 0
            shown = null
            save()
        }
    }
}
