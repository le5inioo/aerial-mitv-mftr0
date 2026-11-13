package app.orchard.tv.wallpaper

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One video held ready at a time for a source that shows videos in turn (owner, 2026-11-13): while
 * the source is idle the next is fetched ahead, while it shows the held one loops, and on leaving the
 * next takes its place and the old one is let go — so the disk and the decoder hold one video, never
 * the whole library.
 *
 * The owner's own films are already on disk, so for them "fetch" is merely choosing the next file and
 * "let go" leaves it there (they are the owner's to keep); a warmer that does nothing is passed. For
 * a streamed source (the aerials) the warmer pre-caches the film's index and opening seconds, and the
 * cache drops the old film the moment the new one begins to arrive.
 */
internal class VideoPool(
    private val prefs: SharedPreferences,
    private val name: String,
    private val scope: CoroutineScope,
    private val catalog: () -> List<String>,
    private val warm: suspend (String) -> Unit = {},
    private val onChanged: () -> Unit = {},
) {
    private val idxKey = "vpool_$name"
    @Volatile private var index = prefs.getInt(idxKey, 0).coerceAtLeast(0)
    @Volatile private var working = false
    @Volatile private var generation = 0

    /** The held entry, seeding to the first while none is chosen; null while the catalog is empty. */
    fun current(): String? {
        val all = catalog()
        if (all.isEmpty()) return null
        val i = if (index in all.indices) index else { index = 0; persist(); 0 }
        return all[i]
    }

    /** Makes the held entry ready while the source is idle, so showing it needs no network. */
    fun prepare() {
        val id = current() ?: return
        warmAsync(id)
        onChanged()
    }

    private fun warmAsync(id: String) {
        if (working) return
        working = true
        val gen = generation
        scope.launch(Dispatchers.IO) {
            try { warm(id) } finally {
                working = false
                if (gen == generation) withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    /** Leaves the held entry: the next one is warmed and becomes the held one. Cycles. */
    fun advance() = advanceAsync { all ->
        val i = if (index in all.indices) index else 0
        index = (i + 1) % all.size
        persist()
        all[index]
    }

    private fun advanceAsync(pick: (List<String>) -> String) {
        if (working) return
        working = true
        val gen = generation
        scope.launch(Dispatchers.IO) {
            val id = try {
                val all = catalog()
                if (all.isEmpty()) null else pick(all)
            } finally { working = false }
            if (id != null) {
                try { warm(id) } finally {
                    if (gen == generation) withContext(Dispatchers.Main) { onChanged() }
                }
            } else if (gen == generation) withContext(Dispatchers.Main) { onChanged() }
        }
    }

    private fun persist() = prefs.edit().putInt(idxKey, index).apply()
}
