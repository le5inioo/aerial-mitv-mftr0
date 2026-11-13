package app.orchard.tv.wallpaper

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import app.orchard.tv.ui.components.UhdCanvas
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.home.fileFramesJam
import app.orchard.tv.ui.home.resetGlassCost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WallpaperKind { Daily, Builtin, Photo, Auto, Live, Video, Source, Webhost }

data class Wallpaper(
    val id: String,
    val kind: WallpaperKind,
    val title: String,
    /** Where the picture was taken, or who took it; shown in the screensaver. */
    val caption: String? = null,
)

/** System: none of Aerial's; the TV's own screensaver starts as the firmware has it (SystemScreensaver). */
enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Photos("photos"), Clock("clock"), FlipClock("flipclock"), Videos("videos"), Webhost("webhost"), System("system");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

/**
 * Everything behind the home screen: which wallpaper is chosen, where pictures come from, and what
 * the screensaver shows. Sources:
 *
 * - Built-in: Aerial's own Mac-style pictures, drawn on the TV ([Gradients]).
 * - Daily: Bing's photo of the day (cn.bing.com, reachable from China without a proxy). The last
 *   eight are kept; "daily:latest" always follows the newest.
 * - Photos: pictures uploaded from a phone through the setup page ([PhoneServer]). "photos:rotate"
 *   shows a different one of them each day (issue #27).
 * - Auto: the previous behaviour, light taken from the focused app's artwork.
 * - Live: Apple's aerial films, moving once Home has sat still a while (ui/home/LiveWallpaper.kt).
 *   Its picture is the frame the film last stopped on, so the glass and the grid match it.
 * - Video: the owner's own films, sent from the phone (ScreensaverVideos), moving the same way
 *   (ui/home/VideoWallpaper.kt), with the frame they stopped on as the picture (issues #410, #583).
 *   "videos:mine" plays all of them in turn; "video:<id>" just that one, on a loop (issue #762).
 * - Source: a TV input's picture behind Home, a set-top box on HDMI (ui/home/SourceWallpaper.kt,
 *   issue #267). Only inputs Android lists can be shown; its picture is a dark built-in one.
 *
 * State is Compose snapshot state, written on the main thread only.
 */
object Wallpapers {
    private const val TAG = "AerialWallpaper"
    const val LATEST_DAILY = "daily:latest"
    private const val DAILY_RETRY_MS = 30 * 60 * 1000L
    /** A video's poster (#1158): sharp enough for its card, small to keep (about 60 KB). */
    private const val POSTER_W = 960
    private const val POSTER_H = 540
    const val AUTO = "auto"
    const val ROTATE_PHOTOS = "photos:rotate"
    const val LIVE = "aerial:live"
    const val VIDEOS = "videos:mine"
    const val VIDEO_PREFIX = "video:"
    const val SOURCE_PREFIX = "source:"
    /**
     * Each app its own picture: the top row's tiles take a wallpaper each, and Home's background
     * follows the tile the focus is on. The assignments live in [perApp].
     */
    const val PER_APP = "perapp"
    /**
     * The owner's own image host on the home network: a picture is fetched from it each time Home
     * comes back (the owner, 2026-11-04), so the wallpaper turns as often as the server deals one
     * out. The address is the owner's server; kept in a preference so it can move.
     */
    const val WEBHOST = "webhost"
    const val WEBHOST_URL = "http://192.168.2.7:5678/"
    /**
     * How many of Home's first tiles can have a picture each: 1 to 6, by place. Past these, a tile
     * follows the app's own light as in "Follow App".
     */
    const val PER_APP_SLOTS = 6
    /** The picture under a TV input's, for the glass and while the input has nothing to show. */
    private const val SOURCE_PICTURE = "hills_night"
    private const val FOLLOW = "follow"
    private const val DEFAULT_FALLBACK = "grad:aurora"

    /** Let go this close to where the picture was taken: the film did not move on from it. */
    private const val SAME_SPOT_MS = 2_000L

    /** The built-ins Personalize offers, one or two of each family; the rest stay for whoever chose them. */
    val FEATURED_BUILTINS = setOf("grad:hills_dawn", "grad:hills_night", "grad:dunes_gold", "grad:sea_sunset", "grad:waves_blue", "grad:aurora")

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()
    private val prefs by lazy { app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE) }
    private val dailyDir by lazy { File(app.filesDir, "daily").apply { mkdirs() } }
    private val photoDir by lazy { File(app.filesDir, "photos").apply { mkdirs() } }
    private val webhostFile by lazy { File(app.filesDir, "webhost.jpg") }
    /** The picture dealt for the next look at the image host, fetched and baked in advance. */
    private val webhostNext by lazy { File(app.filesDir, "webhost-next.jpg") }
    private val liveFrame by lazy { File(app.filesDir, "live-frame.jpg") }
    private val videoFrame by lazy { File(app.filesDir, "video-frame.jpg") }

    /** TV inputs that can play behind Home, by TvInputInfo id and name; set as Home lists inputs. */
    private var sources: List<Pair<String, String>> = emptyList()
    private val bakedDir by lazy { File(app.filesDir, "backdrop").apply { mkdirs() } }
    private val thumbs = HashMap<String, Bitmap>()

    var items by mutableStateOf<List<Wallpaper>>(emptyList()); private set
    var selectedId by mutableStateOf(LATEST_DAILY); private set

    /**
     * The pictures chosen for Home's first tiles, by the tile's place in the row (1 is the
     * leftmost). By place, not by app: a tile keeps its picture when another app is put in it.
     * Compose state: Home recomposes as one is chosen and its picture is baked.
     */
    var perApp by mutableStateOf<Map<Int, String>>(emptyMap()); private set

    /** The baked pictures for [perApp], by tile: switching tiles must not wait for a bake. */
    private var perAppPictures by mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())
    private var perAppGlass by mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())
    private val perAppJobs = HashMap<Int, Job>()

    /**
     * The tile Home is showing. In "Each App" there is no wallpaper of its own to frost, so panels
     * (Personalize, Control Center) and the plate take their glass from this tile's picture
     * ([panelFrost]): without it they were one flat colour, and the Effects setting changed nothing.
     */
    private var shownPerApp by mutableStateOf(1)

    /** The frosted picture panels are made of: the wallpaper's, or in "Each App" the shown tile's. */
    val panelFrost: ImageBitmap?
        get() = frosted?.takeIf { backdrop != null }
            ?: perAppGlass[shownPerApp].takeIf { selectedId == PER_APP }

    /** Home tells which tile it shows, for [panelFrost]. */
    fun notePerAppShown(slot: Int) {
        if (slot in 1..PER_APP_SLOTS) shownPerApp = slot
    }

    /**
     * The pictures kept for the next start, by tile. Baking one takes about two seconds on
     * MiTV-MFTR0 and Home showed the app's light until it was in; read back from disk it takes tens
     * of milliseconds, so Home's first frame at boot already has the picture of its first tile.
     */
    private val perBakedDir by lazy { File(app.filesDir, "per-backdrop").apply { mkdirs() } }

    /**
     * Pictures an earlier build kept by package name (1.81 test builds): [adoptPerAppByPackage]
     * moves each to the tile its app now holds, so a picture chosen then is not lost.
     */
    private var legacyPerApp: Map<String, String> = emptyMap()

    /** The screensaver the owner chose; null while it follows the wallpaper ([screensaver]). */
    private var chosenScreensaver by mutableStateOf<ScreensaverMode?>(ScreensaverMode.Aerial)

    /**
     * The screensaver in effect. Until one is chosen it follows the wallpaper: aerial films with the
     * aerial wallpaper (and in "Follow App", which has no picture), photos with a picture, starting
     * from that picture.
     */
    val screensaver: ScreensaverMode
        get() = chosenScreensaver ?: when {
            isVideo(selectedId) -> ScreensaverMode.Videos
            // "Each App" has no picture of its own for a photo screensaver to start from.
            selectedId == LIVE || selectedId == AUTO || selectedId == PER_APP || isSource(selectedId) -> ScreensaverMode.Aerial
            else -> ScreensaverMode.Photos
        }

    /** A wallpaper that moves behind Home (aerial films, the owner's videos), with a frame as its picture. */
    fun moves(id: String = effectiveId()) = id == LIVE || isVideo(id)

    /**
     * What Home shows behind it now: the wallpaper itself, or in "Each App" the shown tile's
     * choice — a tile may take the aerial films or the owner's videos, which play behind Home
     * while it is the tile in focus (the owner, 2026-11-06).
     */
    private fun effectiveId(): String =
        if (selectedId == PER_APP) perApp[shownPerApp] ?: PER_APP else selectedId

    /** The owner's videos, all in turn or one of them. */
    fun isVideo(id: String = selectedId) = id == VIDEOS || id.startsWith(VIDEO_PREFIX)

    /**
     * Whether Apple's aerials are in use now: the wallpaper, a tile in "Follow Tile", or the
     * screensaver. Nothing fetches a film ahead of time, nor brings in the day's new one, when
     * none of them shows the aerials (owner, 2026-11-12).
     */
    fun aerialEnabled(): Boolean = started &&
        (selectedId == LIVE || (selectedId == PER_APP && perApp.containsValue(LIVE)) ||
            screensaver == ScreensaverMode.Aerial)

    /** The films [id] plays behind Home: all the owner's videos, or the one chosen. Call off the main thread. */
    fun videoFilms(id: String = selectedId): List<ScreensaverVideo> {
        val all = ScreensaverVideos.all(app)
        return if (id.startsWith(VIDEO_PREFIX)) all.filter { it.id == id.removePrefix(VIDEO_PREFIX) } else all
    }

    /** Whether the frame the videos last stopped on is one of [id]'s films. */
    private fun videoFrameIsOf(id: String): Boolean {
        if (!videoFrame.exists()) return false
        if (!id.startsWith(VIDEO_PREFIX)) return true
        return prefs.getString("video_film", null) == id.removePrefix(VIDEO_PREFIX)
    }

    /**
     * Plays as soon as Home shows it and keys do not stop it: the aerial wallpaper always; the
     * owner's videos when set to (issue #360).
     */
    val keepsMoving: Boolean get() = selectedId == LIVE || AerialSettings.wallpaperAlwaysMoves

    fun isSource(id: String = selectedId) = id.startsWith(SOURCE_PREFIX)

    /** The TV input whose picture is the wallpaper, or null. */
    val sourceInput: String? get() = selectedId.takeIf(::isSource)?.removePrefix(SOURCE_PREFIX)

    /** No screensaver chosen: it follows the wallpaper. */
    val screensaverFollows: Boolean get() = chosenScreensaver == null

    /** The home backdrop for the selection, shading baked in; null while loading or in Auto. */
    var backdrop by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * The same picture frosted: blurred small. The glass under the top row, the grid and the
     * panels sample it.
     */
    var frosted by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * Whether the moving wallpaper's frames cannot be had for its glass on this TV: PixelCopy reads
     * the film too slowly to use and its file is not read (TCL MT5879), or the file's frames stuck
     * since the TV started (LiveWallpaper). Then the top row's plate is the smoke alone over the
     * film and panels are one solid colour, rather than the frosted frame of minutes before.
     */
    var filmGlassless by mutableStateOf(false)


    /** Goes up when a moving wallpaper's picture changes, so its card in Personalize is made again. */
    var pictureVersion by mutableIntStateOf(0); private set

    private var backdropJob: Job? = null
    private var started = false

    /**
     * The UI's size in pixels. Pictures are decoded to it: 1080p on most TVs, 4K where the UI is.
     * Portrait where the window is: a monitor stood on end, its box turned to portrait, gives Aerial
     * a 1080×1920 window though it asks for landscape, and a 16:9 picture drawn into it was
     * stretched to three times its height (HK1RBOX-X50, Android 14, #1416 #1255).
     */
    var screenW = 1920; private set
    var screenH = 1080; private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        measure(context).let { (w, h) -> screenW = w; screenH = h }
        selectedId = (prefs.getString("selected", LATEST_DAILY) ?: LATEST_DAILY)
            // A built-in wallpaper from before the Mac-style set: its nearest one now.
            .let { if (it.startsWith("grad:")) "grad:" + Gradients.currentKey(it.removePrefix("grad:")) else it }
        runCatching {
            val json = prefs.getString("per_app", null) ?: return@runCatching
            val o = JSONObject(json)
            val slots = HashMap<Int, String>()
            val byPackage = HashMap<String, String>()
            for (key in o.keys().asSequence()) {
                val id = o.getString(key)
                val slot = key.toIntOrNull()
                // An earlier build kept the pictures by package name; Home moves those to their
                // tiles once it knows which app holds which (adoptPerAppByPackage).
                if (slot != null && slot in 1..PER_APP_SLOTS) slots[slot] = id else byPackage[key] = id
            }
            perApp = slots
            legacyPerApp = byPackage
        }
        chosenScreensaver = when (val key = prefs.getString("screensaver", null)) {
            null -> firstScreensaver()
            // "wallpaper" was a fourth kind in test builds of 1.38, for what following now is.
            FOLLOW, "wallpaper" -> null
            else -> ScreensaverMode.of(key)
        }
        screensaverVideo = prefs.getString("saver_video", null)?.takeIf { it.isNotEmpty() }
        rebuildList()
        sanitizePerApp()
        loadBaked()
        loadPerAppBaked()
        rebuildBackdrop()
        if (dailyEnabled()) scope.launch { refreshDaily() }
        // Deal the first picture into the buffer while nothing is looking at the image host. A
        // first install has no picture at all, so that one goes on screen as soon as it is baked.
        scope.launch {
            if (webhostWanted() && !webhostFile.exists()) pendingShow = true
            prefetchWebHost()
        }
        scope.launch(Dispatchers.IO) { clearStaleUploads() }
        // Installs from before 1.42 have a baked picture but no copy yet.
        scope.launch(Dispatchers.IO) { keepForLockedBoot() }
    }

    /** [context]'s window in pixels, times the panel's scale where Home is drawn at 4K (UhdCanvas). */
    private fun measure(context: Context): Pair<Int, Int> {
        val scale = UhdCanvas.scale(context)
        val m = context.resources.displayMetrics
        return (m.widthPixels * scale).roundToInt() to (m.heightPixels * scale).roundToInt()
    }

    /**
     * Pictures sized for [context]'s window, which is Home's or the screensaver's: [init] may have
     * come from a receiver first, and the box may be turned while Aerial runs. A new size bakes
     * the backdrop again (its key has the size in it).
     */
    fun fitScreen(context: Context) {
        if (!started) return
        val (w, h) = measure(context)
        if (w <= 0 || h <= 0 || (w == screenW && h == screenH)) return
        DiagLog.i("wallpaper size ${screenW}x$screenH -> ${w}x$h")
        screenW = w
        screenH = h
        rebuildBackdrop()
    }

    /**
     * Uploads pass through temp files in the cache folder (ours, CustomIcons', and NanoHTTPD's for
     * each part), deleted when the upload ends. One cut off midway, or the process dying, leaves
     * its file behind, and those piled up (#193). Only those, and only an hour old: the aerial
     * cache beside them keeps itself under 48 MB.
     */
    private fun clearStaleUploads() {
        val before = System.currentTimeMillis() - 60 * 60 * 1000L
        app.cacheDir.listFiles { f ->
            f.isFile && f.lastModified() < before &&
                (f.name.startsWith("upload") || f.name.startsWith("icon") || f.name.startsWith("NanoHTTPD-"))
        }?.forEach { val kb = it.length() / 1024; if (it.delete()) Log.i(TAG, "removed a stale upload, $kb KB") }
    }

    fun select(id: String) {
        val previousId = selectedId
        val wasDaily = dailyEnabled()
        selectedId = id
        prefs.edit { putString("selected", id) }
        // The panels' glass belongs to the film it was taken from. Left in place, a switch
        // between Aerial, the video carousel and a single video kept showing the old film's
        // frame until the next sample (seconds away, or never on a TV where sampling was
        // shut down as too costly). The new wallpaper's own picture takes over at once.
        filmPanelGlass = null
        // The new film may be a different resolution: a 4K aerial that made frame copies too
        // costly must not leave the owner's videos without a sampler for the rest of the run.
        if (id != previousId) resetGlassCost()
        // Out of "Each App", the tiles' own pictures go with the mode. Kept over from a stay in
        // it, they stood in for the wallpaper in every other mode: the picture and the plate
        // changed with every tile stepped, under a photo wallpaper that never moved (the owner,
        // 2026-11-06). Entering the mode again bakes them anew.
        if (id != PER_APP) {
            perAppPictures = emptyMap()
            perAppGlass = emptyMap()
        }
        rebuildBackdrop()
        // A first choice shows the picture dealt in advance, rather than waiting for Home to come
        // back to it; the one after that is dealt while this one is on screen.
        if (id == WEBHOST) refreshWebHost(force = true)
        // A daily photo is fetched only while something shows it: a switch to one must bring it
        // in now, rather than wait for the next six-hour check.
        if (!wasDaily && dailyEnabled()) kickDaily()
    }

    // ---- a picture for each tile ----

    /**
     * A tile whose picture is gone (a photo deleted from the phone page) goes back to the
     * "Follow App" default of its picker (the owner, 2026-11-04). Nothing is assigned here that
     * would stick in the tile's settings: Home shows the focused app's artwork instead.
     */
    private fun sanitizePerApp() {
        val next = perApp.toMutableMap()
        // One daily photo is kept now (owner, 2026-11-12), so a tile that named one of the older
        // dates loses its file as soon as the newer picture arrives. Keep the tile's choice rather
        // than dropping it back to "Follow App": point it at the photo that is on disk today.
        val newestDaily = items.firstOrNull { it.kind == WallpaperKind.Daily }?.id
        var changed = false
        for ((slot, id) in perApp) {
            // "Daily Photo" always points at the newest one: it is valid with no file on disk,
            // and before the first download resolve() has nothing to find. Dropping it here
            // would silently undo the owner's choice at the next start (owner, 2026-11-13).
            if (id == LATEST_DAILY) continue
            // A slot whose picture is gone — a photo or a film deleted — falls back too: the
            // lists are rebuilt first, so what the pickers offer is what resolve() answers for.
            if (resolve(id) != null) continue
            val daily = if (id.startsWith("daily:") && newestDaily != null) newestDaily else null
            if (daily != null) next[slot] = daily else next.remove(slot)
            changed = true
            // Whatever was baked for the old picture must not stand in for the new one's.
            perAppJobs.remove(slot)?.cancel()
            perAppPictures = perAppPictures - slot
            perAppGlass = perAppGlass - slot
            clearPerBaked(slot)
        }
        if (changed) {
            perApp = next
            savePerApp()
            if (selectedId == PER_APP) preparePerApp(next.keys.toList())
        }
    }

    /**
     * The picture chosen for the tile in [slot] (1 is the leftmost), or null when it follows the
     * wallpaper as the tiles past the sixth do.
     */
    fun perAppChoice(slot: Int): String? = perApp[slot]

    /** The picture Home shows for the tile in [slot] in [PER_APP] mode, once baked. */
    fun perAppBackdrop(slot: Int?): ImageBitmap? = slot?.let { perAppPictures[it] }

    /** Its frosted copy, for the plate and the grid. */
    fun perAppFrosted(slot: Int?): ImageBitmap? = slot?.let { perAppGlass[it] }

    /**
     * Gives the tile in [slot] the picture [id], or takes its own away (null) so it follows the
     * wallpaper again. The baked picture is made at once in [PER_APP] mode, so the tile shows it as
     * soon as the owner goes back to Home. A tile that takes the aerial films or the owner's videos
     * plays them behind Home while it is in focus ([moves]); the baked poster stands in until the
     * film's first frame.
     */
    fun setPerApp(slot: Int, id: String?) {
        if (slot !in 1..PER_APP_SLOTS) return
        val wasDaily = dailyEnabled()
        // A tile shows a still, but a film may stand for it: the bake takes the film's poster or
        // its last stopped frame (picture()), so "My Videos" and a single film are offered too.
        val next = perApp.toMutableMap()
        if (id == null) next.remove(slot) else next[slot] = id
        perApp = next
        savePerApp()
        perAppJobs.remove(slot)?.cancel()
        perAppPictures = perAppPictures - slot
        perAppGlass = perAppGlass - slot
        clearPerBaked(slot)
        if (id == WEBHOST) refreshWebHost(force = true)
        if (selectedId == PER_APP) preparePerApp(listOf(slot))
        if (!wasDaily && dailyEnabled()) kickDaily()
    }

    /** Writes [perApp] as {"1": "grad:…", "2": "photo:…"}, by tile. */
    private fun savePerApp() {
        val o = JSONObject()
        perApp.forEach { (slot, id) -> o.put(slot.toString(), id) }
        prefs.edit { putString("per_app", o.toString()) }
    }

    /**
     * Moves the pictures an earlier build kept by package name to the tiles those apps hold now:
     * [pkgs] are Home's tiles in order. Called with Home's apps, once, and the old map is let go.
     */
    fun adoptPerAppByPackage(pkgs: List<String>) {
        // Home calls this as it composes, before its apps have loaded: wait for them.
        if (legacyPerApp.isEmpty() || pkgs.isEmpty()) return
        val moved = perApp.toMutableMap()
        pkgs.take(PER_APP_SLOTS).forEachIndexed { i, pkg ->
            val id = legacyPerApp[pkg] ?: return@forEachIndexed
            // A picture already chosen for that tile by place wins over the one by name.
            if (moved[i + 1] == null) moved[i + 1] = id
        }
        legacyPerApp = emptyMap()
        perApp = moved
        savePerApp()
        for (slot in moved.keys) perAppJobs[slot]?.cancel()
        perAppPictures = emptyMap()
        perAppGlass = emptyMap()
        if (selectedId == PER_APP) preparePerApp(moved.keys.toList())
    }

    /**
     * Bakes the pictures of [slots] that have one. Called with Home's first tiles, so that moving
     * the focus to a tile finds its picture ready instead of the bake's two seconds (MiTV-MFTR0).
     */
    fun preparePerApp(slots: List<Int>, force: Boolean = false) {
        if (!started || selectedId != PER_APP) return
        for (slot in slots) {
            val choice = perApp[slot]
            // The default is "Follow App": no picture of its own, so Home shows the artwork of
            // the app the focus is on, exactly as the "Follow App" wallpaper does. It used to
            // bake a random built-in instead, which ignored the app in the tile (the owner,
            // 2026-11-12).
            if (choice == null) {
                perAppJobs.remove(slot)?.cancel()
                perAppPictures = perAppPictures - slot
                perAppGlass = perAppGlass - slot
                clearPerBaked(slot)
                continue
            }
            val id = choice
            if (!force && (perAppPictures.containsKey(slot) || perAppJobs[slot]?.isActive == true)) continue
            // [force]: a new picture is on its way for this tile — bake it over the old one, which
            // stays on screen until the new one is in (no flash of the wallpaper underneath).
            if (force) perAppJobs.remove(slot)?.cancel()
            DiagLog.i("tile $slot: baking $id")
            perAppJobs[slot] = scope.launch {
                val baked = withContext(Dispatchers.Default) {
                    val wp = resolve(id) ?: return@withContext null
                    // The same treatment as the chosen wallpaper: shaded for Home, frosted for glass.
                    val picture = picture(wp, screenW, screenH) ?: return@withContext null
                    Images.shadeForHome(picture) to Images.frost(picture)
                } ?: return@launch.also { DiagLog.i("tile $slot: no picture of $id") }
                // Chosen again while it was baking: that choice bakes its own picture.
                if (perApp[slot] != choice) return@launch
                DiagLog.i("tile $slot: baked")
                perAppPictures = perAppPictures + (slot to baked.first.asImageBitmap())
                perAppGlass = perAppGlass + (slot to baked.second.asImageBitmap())
                savePerBaked(slot, id, baked.first, baked.second)
            }
        }
    }

    // ---- the tiles' pictures, kept for the next start ----

    /** What a tile's picture was baked from: the picture, the tile, the screen and Aerial's version. */
    private fun perBakedKey(slot: Int, id: String): String {
        // "Daily Photo" is a moving target: keep its baked picture under the concrete date it
        // came from, so a new day's photo is not read back as yesterday's (owner, 2026-11-13).
        val concrete = if (id == LATEST_DAILY) resolve(LATEST_DAILY)?.id ?: id else id
        return "$concrete|$slot|${screenW}x$screenH|${BuildConfig.VERSION_CODE}"
    }

    private fun perFiles(slot: Int) = Triple(
        File(perBakedDir, "slot$slot.key"),
        File(perBakedDir, "slot$slot-home.jpg"),
        File(perBakedDir, "slot$slot-frost.png"),
    )

    /**
     * Puts up the pictures kept for the next start. Tile 1 on the main thread, before Home's first
     * frame, since that is the tile Home opens on; the rest off it, and then whatever no picture was
     * kept for is baked. Reading six back is some hundreds of milliseconds of disk, not of Home.
     */
    private fun loadPerAppBaked() {
        if (selectedId != PER_APP || perApp.isEmpty()) return
        val first = if (perApp[1] != null) 1 else perApp.keys.min()
        putBakedPerApp(first)
        scope.launch(Dispatchers.IO) {
            for ((slot, id) in perApp) {
                if (perAppPictures.containsKey(slot)) continue
                val baked = runCatching { readPerBaked(slot, id) }.getOrNull() ?: continue
                withContext(Dispatchers.Main) {
                    if (perApp[slot] == id && !perAppPictures.containsKey(slot)) {
                        perAppPictures = perAppPictures + (slot to baked.first.asImageBitmap())
                        perAppGlass = perAppGlass + (slot to baked.second.asImageBitmap())
                    }
                }
            }
            // The ones no picture was kept for (a picture just chosen, or a disk cleared): baked.
            withContext(Dispatchers.Main) { preparePerApp(perApp.keys.toList()) }
        }
    }

    /** Puts [slot]'s kept picture up, if there is one and it is of the picture the tile has now. */
    private fun putBakedPerApp(slot: Int) {
        val id = perApp[slot] ?: return
        if (perAppPictures.containsKey(slot)) return
        val baked = runCatching { readPerBaked(slot, id) }.getOrNull() ?: return
        perAppPictures = perAppPictures + (slot to baked.first.asImageBitmap())
        perAppGlass = perAppGlass + (slot to baked.second.asImageBitmap())
    }

    private fun readPerBaked(slot: Int, id: String): Pair<Bitmap, Bitmap>? {
        val (key, homeFile, frostFile) = perFiles(slot)
        if (!key.exists() || !homeFile.exists() || !frostFile.exists()) return null
        if (key.readText() != perBakedKey(slot, id)) return null
        val home = BitmapFactory.decodeFile(homeFile.path) ?: return null
        val frost = BitmapFactory.decodeFile(frostFile.path) ?: return null
        if (home.width != screenW || home.height != screenH) return null
        return home to frost
    }

    private suspend fun savePerBaked(slot: Int, id: String, home: Bitmap, frost: Bitmap) {
        withContext(Dispatchers.IO) {
            runCatching {
                val (key, homeFile, frostFile) = perFiles(slot)
                // The key last, so a write cut short is never taken for a good one.
                key.delete()
                homeFile.outputStream().use { home.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                frostFile.outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
                key.writeText(perBakedKey(slot, id))
            }.onFailure { Log.w(TAG, "could not keep tile $slot's picture", it) }
        }
    }

    private fun clearPerBaked(slot: Int) {
        val (key, homeFile, frostFile) = perFiles(slot)
        key.delete()
        homeFile.delete()
        frostFile.delete()
    }

    /**
     * A new install's screensaver follows the wallpaper. One that was already in use when that came
     * (1.38) keeps what it showed, aerial films, until the owner chooses. Kept either way, so it
     * does not change with a later update.
     */
    private fun firstScreensaver(): ScreensaverMode? {
        val fresh = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
        prefs.edit { putString("screensaver", if (fresh) FOLLOW else ScreensaverMode.Aerial.key) }
        return if (fresh) null else ScreensaverMode.Aerial
    }

    /** The wallpaper's picture, when the photo screensaver starts from it: a picture wallpaper, not the aerial one. */
    fun screensaverPicture(): Wallpaper? {
        if (screensaver != ScreensaverMode.Photos) return null
        // What Home shows, which in "Follow Tile" is the picture of the tile in focus — not the
        // card for the mode itself: the card has no picture, and the screensaver sat on black with
        // "跟随磁贴" for its caption until the next photo came round (the owner, 2026-11-06).
        val shown = effectiveId()
        if (moves(shown) || isSource(shown) || shown == AUTO) return null
        return resolve(shown)?.takeIf { draws(it) }
    }

    /**
     * The pictures the photo screensaver moves through: the owner's own photos, else the daily ones,
     * else the built-in pictures. Only things that draw — "Follow Tile" and "Follow App" are cards
     * that stand for a choice, listed among the built-ins; one of them in the pool left the
     * screensaver on black until the next slide, titled with the card's own name ("跟随磁贴",
     * the owner, 2026-11-06).
     */
    fun photoPictures(): List<Wallpaper> {
        fun pictures(kind: WallpaperKind) = items.filter { it.kind == kind && draws(it) }
        return pictures(WallpaperKind.Photo)
            .ifEmpty { pictures(WallpaperKind.Daily) }
            .ifEmpty { pictures(WallpaperKind.Builtin) }
    }

    /** Whether [wp] has a picture to draw: a card for a choice, or a photo whose file is gone, has none. */
    private fun draws(wp: Wallpaper): Boolean = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:")) != null
        WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.exists() == true
        WallpaperKind.Auto -> false
        else -> true
    }

    fun chooseScreensaver(mode: ScreensaverMode) {
        val wasDaily = dailyEnabled()
        chosenScreensaver = mode
        prefs.edit { putString("screensaver", mode.key) }
        GuardSetup.screensaverModeChanged(app)
        // The photo screensaver falls back to the daily photo when the owner has no photos.
        if (!wasDaily && dailyEnabled()) kickDaily()
    }

    /** The concrete wallpaper the selection points at today. */
    fun resolve(id: String = selectedId): Wallpaper? = when (id) {
        LATEST_DAILY -> items.firstOrNull { it.kind == WallpaperKind.Daily }
        ROTATE_PHOTOS -> items.filter { it.kind == WallpaperKind.Photo }.sortedBy { it.id }.let { photos ->
            if (photos.isEmpty()) resolve(LATEST_DAILY) else photos[Math.floorMod(photoSlot(), photos.size)]
        }
        // Inputs are listed after Home starts; until then the input is taken on trust.
        else -> items.firstOrNull { it.id == id }
            ?: if (isSource(id)) Wallpaper(id, WallpaperKind.Source, id.substringAfterLast('/')) else null
    }

    /**
     * Which turn "My Photos, in turn" is on: the day, or with a shorter interval chosen
     * (AerialSettings.photoMinutes, #1314), the count of such intervals in local time.
     */
    private fun photoSlot(): Long {
        val minutes = AerialSettings.photoMinutes
        if (minutes >= AerialSettings.DAY_MINUTES) return java.time.LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 60_000L / minutes
    }

    /** The turn the rotating photo was last picked for; a new one picks the next photo. */
    private var rotatedOn = Long.MIN_VALUE

    /**
     * Moves "My Photos, in turn" on to the photo for now; Aerial calls it whenever Home comes back,
     * and each minute while Home is in front. Every call also makes sure the image host has a picture
     * dealt in advance: the minute tick must NOT spend it, or a focus arriving later would find the
     * buffer empty. Actually showing it is [refreshWebHost]'s job (a look at the picture, or Home
     * coming back), which then deals the next one.
     */
    fun rotateIfDue() {
        if (!started) return
        prefetchWebHost()
        if (selectedId != ROTATE_PHOTOS || rotatedOn == photoSlot()) return
        rebuildBackdrop()
    }

    // ---- the owner's image host ----

    /** When a picture was last dealt from the image host, so Home's bounces deal once. */
    private var webhostAt = 0L

    private const val WEBHOST_MIN_MS = 10_000L

    /** One fetch at a time: the tiles can be stepped through faster than a picture arrives. */
    @Volatile private var webhostFetching = false

    /** Bumped when the host's address changes, so a picture from the old host is not staged. */
    @Volatile private var webhostGeneration = 0

    /**
     * The next picture, already fetched and baked, waiting for a look at the image host. Showing it
     * is then a local rename and a handful of assignments: no network, no 4K decode, no shading or
     * frosting (all of which used to cost about two seconds on MFTR0, after the focus had landed).
     * The owner, 2026-11-13: a picture should be ready before the focus arrives, not fetched while
     * it waits — step away, deal the next one, and show it the next time the focus comes back.
     */
    private class WebhostStaged(val file: File, val home: Bitmap, val frost: Bitmap)

    @Volatile private var staged: WebhostStaged? = null

    /** A look came while the next picture was still on its way: show it the moment it is ready. */
    @Volatile private var pendingShow = false

    /** Whether the wallpaper or any tile shows the image host's picture. */
    private fun webhostWanted() = selectedId == WEBHOST || perApp.containsValue(WEBHOST)

    /** The address the picture comes from: the owner's, or the one shipped with the build. */
    fun webHostUrl(): String = prefs.getString("webhost_url", null)?.takeIf { it.isNotBlank() } ?: WEBHOST_URL

    /**
     * Points the image host somewhere else (the owner may change servers, 2026-11-04). The last
     * picture stays until the new host answers, rather than Home going blank.
     */
    fun setWebHostUrl(url: String): Boolean {
        val clean = normalizeHost(url) ?: return false
        prefs.edit { putString("webhost_url", clean) }
        DiagLog.i("image host set to $clean")
        // The staged picture came from the old host: it must not stand in for the new one's.
        webhostGeneration++
        dropStaged()
        // Unlike a focus that found the buffer empty, changing the address is an explicit choice:
        // the first picture from the new host goes on screen as soon as it is baked.
        pendingShow = true
        prefetchWebHost()
        return true
    }

    private fun dropStaged() {
        staged = null
        webhostNext.delete()
    }

    /** "192.168.2.7:5678" or "http://host/path" as a URL; null when it cannot be one. */
    private fun normalizeHost(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return runCatching {
            val u = URL(s)
            if (u.host.isNullOrBlank()) null else s.trimEnd()
        }.getOrNull()
    }

    /**
     * The tile in [slot] has the image host's picture: coming to that tile shows the picture dealt
     * for it in advance (the owner, 2026-11-04; made instant 2026-11-13), so stepping along the
     * row changes the picture with the tile without waiting for the network.
     */
    fun refreshPerAppHost(slot: Int) {
        if (selectedId != PER_APP || perApp[slot] != WEBHOST) return
        refreshWebHost(force = true)
    }

    /**
     * Shows the staged picture and deals the one after it. Called when Home comes back, when the
     * focus reaches a tile of the image host's, and when the picture is first chosen: never on a
     * path that draws a frame, because everything expensive was done in [prefetchWebHost].
     *
     * A look that arrives while the next picture is still on its way neither waits nor changes the
     * screen later: the picture already there stays, and the fetched one is shown the next time a
     * look comes, in the same frame. Only a first install — nothing on disk at all — puts the first
     * picture up as soon as it is baked. Quick key repeats used to flash a new picture under a
     * tile the focus had already left (owner, 2026-11-13).
     */
    fun refreshWebHost(force: Boolean = false) {
        if (!started || !webhostWanted()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - webhostAt < WEBHOST_MIN_MS) return
        webhostAt = now
        val ready = staged
        if (ready != null) showStaged(ready)
        else {
            if (!webhostFile.exists()) pendingShow = true
            prefetchWebHost()
        }
    }

    /**
     * Deals one picture from the image host ([WEBHOST_URL]) and bakes it, without showing anything.
     * One picture is held and one fetch runs at a time, so an idle TV does not keep asking the host
     * for pictures; [showStaged] asks for the next one as soon as it uses this one. A fetch that
     * fails keeps the picture already on screen.
     */
    fun prefetchWebHost() {
        if (!started || !webhostWanted()) return
        if (staged != null || webhostFetching) return
        webhostFetching = true
        scope.launch(Dispatchers.IO) {
            val url = webHostUrl()
            val generation = webhostGeneration
            val part = File(app.cacheDir, "webhost-next.part")
            var next: WebhostStaged? = null
            try {
                val ok = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 5_000
                    c.readTimeout = 20_000
                    c.instanceFollowRedirects = true
                    try {
                        check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                        c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(part.path, bounds)
                        check(bounds.outWidth > 0) { "not a picture" }
                        webhostNext.delete()
                        check(part.renameTo(webhostNext)) { "could not keep the next picture" }
                    } finally { c.disconnect() }
                    // Bake while nobody is waiting. Frost first: shadeForHome draws into its input
                    // when that bitmap is mutable (the same order rebuildBackdrop uses).
                    val picture = Images.decodeCover(webhostNext, screenW, screenH) ?: error("could not decode it")
                    val frost = Images.frost(picture)
                    val home = Images.shadeForHome(picture)
                    next = WebhostStaged(webhostNext, home, frost)
                }.isSuccess && generation == webhostGeneration
                if (!ok) next = null
                DiagLog.i("image host ${if (next != null) "staged" else "failed"} ($url)")
            } finally {
                part.delete()
                webhostFetching = false
                val ready = next
                if (ready != null) {
                    staged = ready
                    withContext(Dispatchers.Main) { if (pendingShow) showStaged(ready) }
                } else {
                    webhostNext.delete()
                    // The address changed while this was in flight: deal one from the new host.
                    if (generation != webhostGeneration) {
                        withContext(Dispatchers.Main) { prefetchWebHost() }
                    }
                }
            }
        }
    }

    /**
     * Puts the staged picture on screen. Nothing here touches the network or decodes a picture:
     * the file is already on the TV and the two bitmaps are already baked, so the focus lands on
     * the new picture in the same frame. Keeps them on disk for the next cold start, off this thread.
     */
    private fun showStaged(ready: WebhostStaged) {
        if (staged !== ready) return
        staged = null
        pendingShow = false
        // A bake of the old picture must not land on top of this one.
        backdropJob?.cancel()
        val slots = perApp.filterValues { it == WEBHOST }.keys.toList()
        slots.forEach { perAppJobs.remove(it)?.cancel() }
        // rename(2) replaces the old file atomically: if it fails, the picture on screen stays.
        if (!ready.file.renameTo(webhostFile)) {
            Log.w(TAG, "could not move the staged picture in")
            return
        }
        val home = ready.home.asImageBitmap()
        val glass = ready.frost.asImageBitmap()
        synchronized(thumbs) { thumbs.remove(WEBHOST) }
        var key: String? = null
        if (selectedId == WEBHOST) {
            backdrop = home
            frosted = glass
            key = bakedKey(Wallpaper(WEBHOST, WallpaperKind.Webhost, ""))
            bakedKey = key
        }
        if (slots.isNotEmpty()) {
            val pictures = perAppPictures.toMutableMap()
            val glasses = perAppGlass.toMutableMap()
            slots.forEach { pictures[it] = home; glasses[it] = glass }
            perAppPictures = pictures
            perAppGlass = glasses
        }
        val savedKey = key
        scope.launch(Dispatchers.IO) {
            if (savedKey != null) saveBaked(savedKey, ready.home, ready.frost)
            slots.forEach { savePerBaked(it, WEBHOST, ready.home, ready.frost) }
        }
        DiagLog.i("image host: showed the staged picture")
        // Deal the next one at once, while the focus is elsewhere or Home is in the background.
        prefetchWebHost()
    }

    // ---- pictures ----

    private fun fileFor(w: Wallpaper): File? = when (w.kind) {
        WallpaperKind.Daily -> File(dailyDir, w.id.removePrefix("daily:") + ".jpg")
        WallpaperKind.Photo -> File(photoDir, w.id.removePrefix("photo:") + ".jpg")
        else -> null
    }

    /**
     * For the photo screensaver: a portrait photo of the owner's whole, on a blurred copy of itself
     * (Images.decodeWhole, issue #647); anything else as [picture]. Call off the main thread.
     */
    fun saverPicture(wp: Wallpaper, w: Int, h: Int): Bitmap? =
        if (wp.kind == WallpaperKind.Photo) fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeWhole(it, w, h) }
        else picture(wp, w, h)

    /** Full picture at [w]×[h] without shading, for the photo screensaver. Call off the main thread. */
    fun picture(wp: Wallpaper, w: Int, h: Int): Bitmap? = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:"))?.let { Gradients.render(it, w, h) }
        WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
        WallpaperKind.Auto -> null
        // Until a film has stopped on a frame, the first built-in stands in.
        WallpaperKind.Live -> liveFrame.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
            ?: Gradients.render(Gradients.presets.first(), w, h)
        WallpaperKind.Video -> videoPicture(wp.id, w, h) ?: Gradients.render(Gradients.presets.first(), w, h)
        WallpaperKind.Source -> Gradients.byKey(SOURCE_PICTURE)?.let { Gradients.render(it, w, h) }
        WallpaperKind.Webhost -> webhostFile.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
    }

    /** The frame one of [id]'s films last stopped on, else the first film's poster or opening frame. */
    private fun videoPicture(id: String, w: Int, h: Int): Bitmap? =
        videoFrame.takeIf { videoFrameIsOf(id) }?.let { Images.decodeCover(it, w, h) } ?: firstVideoFrame(id, w, h)

    /** A film's own picture for its card, kept beside it, so it goes when the film is deleted. */
    private fun posterOf(film: ScreensaverVideo) = File(film.file.parentFile, "poster.jpg")

    /** The poster kept for the owner's video [film], if one was; never read from the film itself. */
    fun videoPoster(film: String, w: Int, h: Int): Bitmap? =
        videoFilms(VIDEOS).firstOrNull { it.id == film }?.let(::posterOf)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }

    private fun keepPoster(film: ScreensaverVideo, frame: Bitmap) {
        val poster = posterOf(film)
        if (poster.exists()) return
        val part = File(poster.path + ".part")
        runCatching {
            part.outputStream().use { Images.cover(frame, POSTER_W, POSTER_H).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            check(part.renameTo(poster))
        }.onFailure { part.delete(); Log.w(TAG, "could not keep a video's poster", it) }
    }

    /**
     * The first film's poster, else its opening frame, kept as the poster once read. The read gave
     * nothing at times on TCL MT9655 (issue #1158, 4K films, perhaps while one played behind Home),
     * and the card then showed the first built-in picture until Aerial restarted. Not read where
     * the media server would jam on the file (TCL's extractor, TCL MT5879; issue #1188).
     */
    private fun firstVideoFrame(id: String, w: Int, h: Int): Bitmap? {
        val film = videoFilms(id).firstOrNull() ?: return null
        val poster = posterOf(film)
        if (w <= POSTER_W && poster.exists()) Images.decodeCover(poster, w, h)?.let { return it }
        if (filmGlassless || fileFramesJam(app)) return null
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(film.file.path)
            val frame = mmr.getFrameAtTime(0) ?: return null.also { DiagLog.i("video poster: no frame of ${film.id}") }
            keepPoster(film, frame)
            Images.cover(frame, w, h)
        } catch (e: Exception) {
            DiagLog.i("video poster: ${film.id} not read (${e.javaClass.simpleName})")
            null
        } finally { runCatching { mmr.release() } }
    }

    /** Drops the thumbnails (about 330 KB each); the picker makes them again when it opens. */
    fun trimMemory() {
        synchronized(thumbs) { thumbs.clear() }
        // The staged picture holds a full-size home bitmap as well as its frost: the price of
        // showing it in the same frame. Give that memory back when the system is short of it;
        // the file stays, and the next idle moment deals and bakes another one.
        staged = null
    }

    /** A 16:9 thumbnail for pickers and the phone page. Call off the main thread. */
    fun thumbnail(id: String): Bitmap? {
        // "Each App" has no picture of its own: its card shows the first one chosen for an app.
        if (id == PER_APP) {
            return perApp.values.firstOrNull()?.let { thumbnail(it) }
                ?: Gradients.render(Gradients.presets.first(), 384, 216)
        }
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        // A frame kept meanwhile (useLiveFrame) makes this one old: shown, not kept.
        val version = pictureVersion
        val wp = (if (id == LATEST_DAILY || id == ROTATE_PHOTOS) resolve(id) else items.firstOrNull { it.id == id }) ?: return null
        val b = when (wp.kind) {
            WallpaperKind.Auto -> Gradients.render(Gradients.autoThumb, 384, 216)
            // Not kept while no frame of the film can be had: the next look tries again (issue #1158).
            WallpaperKind.Video -> videoPicture(id, 384, 216) ?: return Gradients.render(Gradients.presets.first(), 384, 216)
            else -> picture(wp, 384, 216)
        }
        if (b != null) synchronized(thumbs) { if (version == pictureVersion) thumbs[id] = b }
        return b
    }

    private fun rebuildBackdrop() {
        backdropJob?.cancel()
        val id = selectedId
        backdropJob = scope.launch {
            // Neither follows a picture of its own: "Follow App" takes the focused app's light,
            // "Each App" the picture chosen for that app ([perAppBackdrop]).
            if (id == AUTO || id == PER_APP) {
                backdrop = null
                frosted = null
                return@launch
            }
            rotatedOn = photoSlot()
            val wp = resolve(id) ?: resolve(DEFAULT_FALLBACK)
            val key = wp?.let(::bakedKey)
            if (key != null && key == bakedKey && backdrop != null) return@launch
            val (bmp, frost) = withContext(Dispatchers.Default) {
                val picture = wp?.let { picture(it, screenW, screenH) }
                // Frosted from the picture before the home shading: the glass sits low on the
                // screen, where the shading is darkest, and would read as a grey slab.
                val frost = picture?.let(Images::frost)
                // A film's picture is its frame as it was, as the film is not shaded (HomeBackground).
                if (wp?.kind == WallpaperKind.Live || wp?.kind == WallpaperKind.Video) picture to frost
                else picture?.let(Images::shadeForHome) to frost
            }
            backdrop = bmp?.asImageBitmap()
            val glass = frost?.asImageBitmap()
            frosted = glass
            // A moving wallpaper just chosen: its panels take this first picture (the film's
            // last stopped frame, or its poster) at once, so switching between Aerial, the
            // video carousel and one video changes the menu's background immediately rather
            // than waiting for the first frame sampled from the new player.
            if (moves(id) && filmPanelGlass == null && glass != null) {
                filmPanelGlass = "$id/start" to glass
            }
            if (key != null && bmp != null && frost != null) saveBaked(key, bmp, frost)
        }
    }

    // ---- the baked backdrop, kept for the next start ----

    /**
     * What the backdrop on disk was baked from; null when there is none. Baking takes a cold start
     * about two seconds on Xiaomi MiTV-MFTR0 (decoding, or drawing a built-in picture pixel by pixel,
     * then shading and frosting), and Home showed black, then the focused app's light, meanwhile
     * (issues #277, #294). Reading the baked pair back takes tens of milliseconds.
     */
    private var bakedKey: String? = null

    /** Changes with the picture behind [wp], the screen size, and Aerial's version (the baking may change). */
    private fun bakedKey(wp: Wallpaper): String {
        val stamp = when (wp.kind) {
            WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.lastModified() ?: 0L
            WallpaperKind.Live -> liveFrame.lastModified()
            WallpaperKind.Video -> if (videoFrameIsOf(wp.id)) videoFrame.lastModified() else 0L
            WallpaperKind.Webhost -> webhostFile.lastModified()
            else -> 0L
        }
        // "clear": a film's picture baked unshaded, not one kept from when it was shaded.
        val film = if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) "|clear" else ""
        return "${wp.id}|$stamp|${screenW}x$screenH|${BuildConfig.VERSION_CODE}$film"
    }

    /**
     * Puts the last baked backdrop up at once, on the main thread, so Home's first frame has it. Even
     * one that is out of date (a new day's photo): it is the picture Home last showed, and the fresh
     * one crossfades in once baked. Not in "Follow App", which has no backdrop.
     */
    private fun loadBaked() {
        if (selectedId == AUTO || selectedId == PER_APP) return
        runCatching {
            val key = File(bakedDir, "key").takeIf { it.exists() }?.readText() ?: return
            val home = BitmapFactory.decodeFile(File(bakedDir, "home.jpg").path) ?: return
            val frost = BitmapFactory.decodeFile(File(bakedDir, "frost.png").path) ?: return
            if (home.width != screenW || home.height != screenH) return
            backdrop = home.asImageBitmap()
            frosted = frost.asImageBitmap()
            bakedKey = key
        }.onFailure { Log.w(TAG, "could not read the baked backdrop", it) }
    }

    private suspend fun saveBaked(key: String, home: Bitmap, frost: Bitmap) {
        bakedKey = key
        withContext(Dispatchers.IO) {
            runCatching {
                // The key last, so a write cut short is never taken for a good one.
                File(bakedDir, "key").delete()
                File(bakedDir, "home.jpg").outputStream().use { home.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                File(bakedDir, "frost.png").outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(bakedDir, "key").writeText(key)
            }.onFailure { Log.w(TAG, "could not keep the baked backdrop", it) }
            keepForLockedBoot()
        }
    }

    /**
     * Before the user is unlocked at power-on, Home can read only device-protected storage, so the
     * baked picture is copied there and MainActivity shows it until the rest can be read. Only
     * that picture: settings, the parental PIN, photos and icons stay in credential storage. Not
     * in "Follow App", which has no picture of its own.
     */
    fun lockedBootBackdrop(context: Context): File? =
        if (Build.VERSION.SDK_INT >= 24) File(context.createDeviceProtectedStorageContext().filesDir, "boot-backdrop.jpg") else null

    private fun keepForLockedBoot() {
        val copy = lockedBootBackdrop(app) ?: return
        runCatching {
            val home = File(bakedDir, "home.jpg")
            if (selectedId == AUTO || selectedId == PER_APP || !home.exists()) { copy.delete(); return }
            if (copy.exists() && copy.lastModified() >= home.lastModified()) return
            val part = File(copy.parentFile, copy.name + ".part")
            home.copyTo(part, overwrite = true)
            check(part.renameTo(copy))
        }.onFailure { Log.w(TAG, "could not keep the backdrop for a locked boot", it) }
    }

    /**
     * The frame a moving wallpaper film stopped on becomes the wallpaper's picture: frosted
     * and kept for the next start. Returns once Home has it. [frame] is screen-sized and is used up.
     * It was [atMs] into [film], where the film picks up when it next moves ([liveSpot]).
     */
    /**
     * [frame] shows as the picture at once, before [useLiveFrame] has kept and frosted it: the
     * aerial film let go into an app shows its last frame rather than black (LiveWallpaper).
     */
    fun showLiveFrame(frame: Bitmap) {
        if (!moves()) return
        backdropJob?.cancel()
        backdrop = frame.asImageBitmap()
    }

    /** A frame of the aerial film made ready to be the picture ([prepareLiveFrame]), its glass made. */
    class PreparedFrame(val frame: Bitmap, val frost: Bitmap)

    /** The slow part of a picture ahead of time, off the main thread: its glass (Images.frost). */
    suspend fun prepareLiveFrame(frame: Bitmap): PreparedFrame =
        withContext(Dispatchers.Default) { PreparedFrame(frame, Images.frost(frame)) }

    /**
     * [prepared] becomes the moving wallpaper's picture at once, as film [film] at [atMs]; its file
     * and the baked Home are written after, apart, so Home coming back on it does not wait for them
     * (the screensaver's pictures: LiveWallpaper.SaverFrames for the aerials, VideoSaverFrames for
     * the owner's videos). Not a film the wallpaper does not play (another video than the one chosen).
     */
    fun applyLiveFrame(prepared: PreparedFrame, film: String, atMs: Long): Boolean {
        val id = selectedId
        if (!moves()) return false
        val video = isVideo(id)
        if (id.startsWith(VIDEO_PREFIX) && film != id.removePrefix(VIDEO_PREFIX)) return false
        backdropJob?.cancel()
        if (video) videoLeftHere = null else leftHere = null
        val key = if (video) "video" else "live"
        prefs.edit { putString("${key}_film", film).putLong("${key}_at", atMs).remove("${key}_left_film").remove("${key}_left_at") }
        // The frame is both the film's own card and the card that plays them all in turn (useLiveFrame).
        synchronized(thumbs) { thumbs.remove(id); if (video) { thumbs.remove(VIDEOS); thumbs.remove(VIDEO_PREFIX + film) } }
        backdrop = prepared.frame.asImageBitmap()
        frosted = prepared.frost.asImageBitmap()
        pictureVersion++
        val target = if (video) videoFrame else liveFrame
        scope.launch(Dispatchers.IO) {
            val part = File(target.path + ".part")
            runCatching { part.outputStream().use { prepared.frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the film's frame", it) }
            if (part.exists() && !part.renameTo(target)) part.delete()
            if (video) videoFilms(VIDEOS).firstOrNull { it.id == film }?.let { keepPoster(it, prepared.frame) }
            if (selectedId == id) resolve(id)?.let { saveBaked(bakedKey(it), prepared.frame, prepared.frost) }
        }
        return true
    }

    suspend fun useLiveFrame(frame: Bitmap, film: String, atMs: Long, wanted: () -> Boolean = { true }): Boolean {
        if (!moves()) return false
        val id = effectiveId()
        val key = if (isVideo(id)) "video" else "live"
        val target = if (isVideo(id)) videoFrame else liveFrame
        val part = File(target.path + ".part")
        // The slow part first, the spot, the file and the picture after, all at once: written
        // first, the spot had caught up while Home, back in between, still showed the old
        // picture (0.5-1.5 s on TCL MT5879). [wanted] no longer, nothing changes.
        val (bmp, frost) = withContext(Dispatchers.Default) {
            runCatching { part.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the film's frame", it) }
            // Not shaded, as the film is not (HomeBackground).
            Images.frost(frame).let { frost -> frame to frost }
        }
        // "Each App": the frame belongs to the tile's film, not to the wallpaper row — the row
        // still points at "跟随磁贴" itself, and that is no reason to throw the frame away.
        if (!wanted() || (selectedId != PER_APP && selectedId != id)) {
            part.delete()
            return false
        }
        backdropJob?.cancel()
        if (part.exists() && !part.renameTo(target)) part.delete()
        if (isVideo(id)) videoLeftHere = null else leftHere = null
        prefs.edit { putString("${key}_film", film).putLong("${key}_at", atMs).remove("${key}_left_film").remove("${key}_left_at") }
        // The film's own card keeps a picture when another film is chosen.
        if (isVideo(id)) withContext(Dispatchers.IO) { videoFilms(VIDEOS).firstOrNull { it.id == film }?.let { keepPoster(it, frame) } }
        // The frame is both the film's own card and the card that plays them all in turn.
        synchronized(thumbs) { thumbs.remove(id); if (isVideo(id)) { thumbs.remove(VIDEOS); thumbs.remove(VIDEO_PREFIX + film) } }
        backdrop = bmp.asImageBitmap()
        frosted = frost.asImageBitmap()
        pictureVersion++
        if (selectedId == id) resolve(id)?.let { saveBaked(bakedKey(it), bmp, frost) }
        return true
    }

    /**
     * The last frame of a film or a video the wallpaper has just been switched away from, as the
     * picture where it was (2026-11-04, the owner asked for the switch to fade): the still the new
     * wallpaper fades in from is where the film was, not a poster taken earlier. Unlike
     * [useLiveFrame] it does not mind that the selection has moved on — that is the point — and
     * its glass is left as it was: the new wallpaper's bake makes a fresh one. Its file and the
     * spot are written apart, so choosing this kind again goes on from the frame.
     */
    fun leaveBehind(frame: Bitmap, film: String, atMs: Long, video: Boolean) {
        // Not cancelled and restarted rather than trusted: the new wallpaper's bake, started when
        // it was chosen, may already have put its picture up before this frame was copied — then
        // the frame must go over it and the bake again, or the switch would end on the old film.
        backdropJob?.cancel()
        bakedKey = null
        backdrop = frame.asImageBitmap()
        leftHere = null
        val target = if (video) videoFrame else liveFrame
        val key = if (video) "video" else "live"
        prefs.edit {
            putString("${key}_film", film).putLong("${key}_at", atMs)
            remove("${key}_left_film").remove("${key}_left_at")
        }
        scope.launch(Dispatchers.IO) {
            val part = File(target.path + ".part")
            runCatching { part.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the frame the film left on", it) }
            if (part.exists() && !part.renameTo(target)) part.delete()
        }
        rebuildBackdrop()
    }

    /**
     * Where the aerial film goes on from: where it was let go without a frame taken ([leftLiveSpot]),
     * else the moment the wallpaper's picture was taken from, so the film starts again on the
     * picture on screen rather than cutting to another place. Null until one has stopped on a frame.
     */
    fun liveSpot(): Pair<String, Long>? = leftLiveSpot() ?: livePictureSpot()

    /** The film and moment the wallpaper's picture was taken from. */
    fun livePictureSpot(): Pair<String, Long>? {
        val film = prefs.getString("live_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_at", 0L)
    }

    /**
     * Where the aerial film was let go with no frame taken, past the picture: Home went behind an
     * app, or a screensaver of aerials played on from the wallpaper's film. Without it the film
     * started again from the same picture every time, and the wallpaper never changed (Weibo
     * report, 2026-10-28). Null once the picture has caught up ([useLiveFrame]).
     */
    fun leftLiveSpot(): Pair<String, Long>? {
        leftHere?.let { return it }
        val film = prefs.getString("live_left_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_left_at", 0L)
    }

    /**
     * The newest left spot, ahead of prefs: the screensaver moves it on every second, so Home comes
     * back on its film and moment, and writes it to prefs only now and then ([leaveLiveFilm]).
     */
    @Volatile
    private var leftHere: Pair<String, Long>? = null

    /**
     * The aerial film was let go at [atMs] into [film] with no frame taken: it goes on from there.
     * Not [persist]ed: kept in memory only, for a screensaver that reports where it is every second.
     */
    /** Forgets where the film was let go: it goes on from the picture's own moment ([liveSpot]). */
    fun dropLeftLiveSpot() {
        leftHere = null
        prefs.edit { remove("live_left_film").remove("live_left_at") }
    }

    fun leaveLiveFilm(film: String, atMs: Long, persist: Boolean = true) {
        // Kept whatever the wallpaper: a screensaver of aerials over another one leaves its film
        // here too, so the aerial wallpaper chosen later begins on it (AerialSet).
        if (!persist) { leftHere = film to atMs; return }
        val picture = livePictureSpot()
        // Let go where the picture is (stopped there, or never moved): nothing to catch up.
        if (picture != null && picture.first == film && abs(picture.second - atMs) < SAME_SPOT_MS) {
            leftHere = null
            prefs.edit { remove("live_left_film").remove("live_left_at") }
        } else {
            leftHere = film to atMs
            prefs.edit { putString("live_left_film", film).putLong("live_left_at", atMs) }
        }
    }

    /**
     * Where the owner's video wallpaper was let go (into an app, or a screensaver of its own), held
     * in memory until a frame of it, or a later one, is the picture: a screensaver of their videos
     * starts from here (HomeVideo.start), as the picture behind the app may still be on its way.
     */
    @Volatile
    private var videoLeftHere: Pair<String, Long>? = null

    fun leaveVideo(film: String, atMs: Long) { videoLeftHere = film to atMs }

    fun videoLeftSpot(): Pair<String, Long>? = videoLeftHere

    /** The same for the owner's videos: which one and where it last stopped. */
    fun videoSpot(): Pair<String, Long>? {
        val film = prefs.getString("video_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("video_at", 0L)
    }

    /**
     * The owner's video [film] was let go at [atMs] with no frame copied: it goes on from there
     * when its tile is back in focus. A tile keeps its own baked picture, so the frame is not
     * copied on the way out — that copy holds the main thread (copyFrameNow) and read as a stutter
     * on every tile stepped past (the owner, 2026-11-06).
     */
    fun leaveVideoFilm(film: String, atMs: Long) {
        prefs.edit { putString("video_film", film).putLong("video_at", atMs) }
        rememberVideoPosition(film, atMs)
    }

    /** Where each film last played, so a tile met again goes on from there. In memory only. */
    private val videoPositions = mutableMapOf<String, Long>()

    fun rememberVideoPosition(film: String?, atMs: Long) {
        if (film != null && atMs > 0L) videoPositions[film] = atMs
    }

    /** Where [film] last played: its own place in memory, else the one left at (prefs), else 0. */
    fun videoPosition(film: String): Long =
        videoPositions[film]
            ?: prefs.getLong("video_at", 0L).takeIf { prefs.getString("video_film", null) == film }
            ?: 0L

    /**
     * The one of the owner's films the screensaver plays on a loop, or null for all of them in
     * turn. Kept beside the screensaver's kind ([ScreensaverMode.Videos]); a film deleted from the
     * phone page clears it back.
     */
    var screensaverVideo by mutableStateOf<String?>(null); private set

    fun chooseScreensaverVideo(id: String?) {
        screensaverVideo = id?.takeIf { it.isNotEmpty() }
        prefs.edit { putString("saver_video", screensaverVideo ?: "") }
    }

    /** The owner's videos changed (sent or deleted from the phone): their cards come or go. */
    fun videosChanged() {
        synchronized(thumbs) { thumbs.keys.removeAll { isVideo(it) } }
        rebuildList()
        sanitizePerApp()
        // The film the screensaver looped gone: all of them in turn again.
        val loop = screensaverVideo
        if (loop != null && items.none { it.id == VIDEO_PREFIX + loop }) chooseScreensaverVideo(null)
        // The film the frame was taken from gone: so is the frame.
        val spot = prefs.getString("video_film", null)
        if (spot != null && items.none { it.id == VIDEO_PREFIX + spot }) {
            videoFrame.delete()
            prefs.edit { remove("video_film").remove("video_at") }
        }
        // The chosen one deleted: the others in turn, if any are left.
        if (isVideo() && items.none { it.id == selectedId }) select(if (items.any { it.id == VIDEOS }) VIDEOS else LATEST_DAILY)
        else if (isVideo()) rebuildBackdrop()
    }

    /** The TV inputs Home lists that can play behind it, as (TvInputInfo id, name). */
    fun setSources(list: List<Pair<String, String>>) {
        if (list == sources) return
        sources = list
        rebuildList()
    }

    /**
     * The glass for a moving wallpaper film that has just stopped, from a small frame near it: the
     * plate can stand over the paused film at once, before the exact frame is decoded for
     * [useLiveFrame]. [frame] is used up.
     */
    suspend fun useLiveFrost(frame: Bitmap) {
        if (effectiveId() != LIVE) return
        backdropJob?.cancel()
        // The frame the plate's glass was made from while the film moved: that glass, at once.
        frosted = filmFrost?.takeIf { frame === filmFrostOf }
            ?: withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
    }

    /**
     * The glass for a moving film: a small frame of it taken every few seconds while it plays
     * (LiveWallpaper, VideoWallpaper), frosted. Null until the first, when [frosted] (the frame
     * the film started from) stands in.
     */
    var filmFrost by mutableStateOf<ImageBitmap?>(null); private set
    private var filmFrostOf: Bitmap? = null

    /**
     * The panels' glass while a moving wallpaper plays: the first frame taken of the film now
     * showing, keyed by that film. The plate's [filmFrost] keeps up with the film every few
     * seconds, but a panel that followed it changed under its own text; the glass a panel opened
     * with therefore stays (LiquidGlass). Keyed by the film, it follows the wallpaper as it rolls
     * on to the next one instead of staying on a frame of the film before (owner, 2026-11-11).
     * Not cleared when the film starts again: the same film keeps the same glass, so a panel does
     * not flicker as the player restarts.
     */
    var filmPanelGlass by mutableStateOf<Pair<String, ImageBitmap>?>(null); private set

    suspend fun useFilmFrost(frame: Bitmap, clip: String? = null) {
        if (!moves()) return
        val glass = withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
        filmFrost = glass
        filmFrostOf = frame
        // One glass per film, not one per frame: the panel changes once as the film changes.
        if (clip != null && filmPanelGlass?.first != clip) filmPanelGlass = clip to glass
    }

    /**
     * Where no frame can be taken while the film moves (LiveWallpaper.sampleGlass): the glass of the
     * frame it started from ([frosted]), blurred down to its broad colours, so the shapes that move
     * on behind the plate do not show twice through it.
     */
    suspend fun useStillFilmFrost() {
        val start = frosted ?: return
        if (!moves()) return
        filmFrostOf = null
        filmFrost = withContext(Dispatchers.Default) {
            val src = start.asAndroidBitmap()
            val quarter = Bitmap.createScaledBitmap(src, src.width / 4, src.height / 4, true)
            Images.frost(Bitmap.createScaledBitmap(quarter, 16, 9, true))
        }.asImageBitmap()
    }

    fun dropFilmFrost() {
        filmFrost = null
        filmFrostOf = null
    }

    private val clipGlassDir by lazy { File(app.filesDir, "clip-glass").apply { mkdirs() } }
    private val clipGlassMade = mutableSetOf<String>()

    /**
     * The grid's glass while the aerial wallpaper shows [clip][showClipGlass]: one for each film,
     * made once from the first frame taken of it ([keepClipGlass]) and kept on disk, so the grid
     * comes in on the same glass wherever the film stopped, and a film met again has it at once
     * (issue #727: a glass made where the film stopped came in seconds late, or changed in sight).
     */
    var clipGlass by mutableStateOf<Pair<String, ImageBitmap>?>(null); private set

    /** Shows [clip]'s glass if it has one: from memory, else from disk. */
    suspend fun showClipGlass(clip: String?) {
        if (clip == null) return
        val file = File(clipGlassDir, "$clip.png")
        val bmp = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null } ?: return
        val glass = bmp.asImageBitmap()
        if (clipGlass?.first != clip) clipGlass = clip to glass
        // The aerial films roll on to a new one: its glass is wanted by the panels even where
        // taking frames while the film moves was shut down as too costly (glassCostly). The
        // key matches the one sampleGlass uses, so a running sampler will not make it twice.
        val key = "${selectedId}/$clip"
        if (moves() && filmPanelGlass?.first != key) filmPanelGlass = key to glass
    }

    /** [frame], a small frame of [clip], becomes its glass unless it has one already. */
    suspend fun keepClipGlass(clip: String, frame: Bitmap, showing: () -> String?) {
        if (clip in clipGlassMade) return
        clipGlassMade += clip
        val file = File(clipGlassDir, "$clip.png")
        val bmp = withContext(Dispatchers.IO) {
            if (file.exists()) return@withContext BitmapFactory.decodeFile(file.path)
            val started = System.nanoTime()
            val frost = Images.frost(frame)
            DiagLog.i("clip glass made in ${(System.nanoTime() - started) / 1_000_000} ms")
            runCatching { file.outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                .onFailure { Log.w(TAG, "could not keep the clip's glass", it) }
            frost
        } ?: return
        if (showing() == clip) clipGlass = clip to bmp.asImageBitmap()
    }

    /** Puts back the glass from before [useLiveFrost] when the exact frame could not be had. */
    fun restoreFrost(before: ImageBitmap?) {
        if (selectedId == LIVE && before != null) frosted = before
    }

    private fun rebuildList() {
        val daily = readDailyIndex().map { Wallpaper("daily:${it.first}", WallpaperKind.Daily, it.second, it.third) }
        val zh = Locale.getDefault().language == "zh"
        val builtins = Gradients.presets.map { Wallpaper("grad:${it.key}", WallpaperKind.Builtin, if (zh) it.nameZh else it.nameEn) }
        val photos = (photoDir.listFiles { f -> f.extension == "jpg" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Wallpaper("photo:${it.nameWithoutExtension}", WallpaperKind.Photo, if (zh) "我的照片" else "My Photo") }
        val auto = Wallpaper(AUTO, WallpaperKind.Auto, if (zh) "跟随 App" else "Follow App")
        val perAppCard = Wallpaper(PER_APP, WallpaperKind.Builtin, if (zh) "跟随磁贴" else "Follow Tile")
        val live = Wallpaper(LIVE, WallpaperKind.Live, if (zh) "航拍" else "Aerial")
        // All of them in turn, then each on its own (#762); the pickers show the first only for two or more.
        val films = ScreensaverVideos.all(app)
        val videos = if (films.isEmpty()) emptyList() else
            listOf(Wallpaper(VIDEOS, WallpaperKind.Video, if (zh) "我的视频 · 轮播" else "My Videos, In Turn")) +
                films.map { Wallpaper(VIDEO_PREFIX + it.id, WallpaperKind.Video, it.name.substringBeforeLast('.').ifBlank { it.name }) }
        val inputs = sources.map { (id, name) -> Wallpaper(SOURCE_PREFIX + id, WallpaperKind.Source, name) }
        val webhost = Wallpaper(WEBHOST, WallpaperKind.Webhost, if (zh) "个人图床" else "My Image Host")
        items = listOf(live) + videos + daily + builtins + photos + auto + perAppCard + listOf(webhost) + inputs
    }

    // A tile without its own choice ("Follow App" in the tile's picker) has no baked picture:
    // Home shows the focused app's artwork instead (see [preparePerApp]), as the "Follow App"
    // wallpaper does. It used to bake a random built-in for the day; the owner asked for the
    // app's own light instead (2026-11-12).

    // ---- daily photos ----

    /**
     * Whether one of Bing's daily photos is on screen now: the wallpaper, a tile, "My Photos in
     * turn" with no photos of the owner's, or the photo screensaver's pool in that same case
     * ([photoPictures]). Bing is asked for a photo — once at a time, and only one is kept — only
     * while this is true (owner, 2026-11-12).
     */
    fun dailyEnabled(): Boolean {
        if (!started) return false
        if (selectedId == LATEST_DAILY || selectedId.startsWith("daily:")) return true
        if (selectedId == PER_APP && perApp.values.any { it == LATEST_DAILY || it.startsWith("daily:") }) return true
        // With no photos of the owner's, both the rotating wallpaper and the photo screensaver
        // fall back to the daily one.
        if (items.none { it.kind == WallpaperKind.Photo } &&
            (selectedId == ROTATE_PHOTOS || screensaver == ScreensaverMode.Photos)) return true
        return false
    }

    /** Brings in the day's photo at once when a choice starts showing it; off the main thread. */
    private fun kickDaily() {
        if (dailyEnabled()) scope.launch { refreshDaily() }
    }

    private fun readDailyIndex(): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(File(dailyDir, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("date"), o.getString("title"), o.getString("caption"))
        }.filter { File(dailyDir, it.first + ".jpg").exists() }
    }.getOrDefault(emptyList())

    private var lastDailyRefresh = 0L
    private var dailyFailed = false

    /**
     * Bing publishes one photo a day, at midnight in China; checking a few times a day is plenty,
     * plus once the day has turned (the date-change broadcast, or the first look after it), retried
     * every half hour until the new photo is out: a TV left on Home overnight kept yesterday's
     * photo for up to six hours (issue #286). A check that failed, usually because the TV was on
     * before its network was, is retried as soon as the network connects ([networkCameUp]), or
     * else a few minutes later (issue #129).
     */
    fun refreshDailyIfStale(networkCameUp: Boolean = false) {
        // No periodic check, network-recovery retry or midnight fetch while nothing shows a daily
        // photo (owner, 2026-11-12).
        if (!started || !dailyEnabled()) return
        val since = System.currentTimeMillis() - lastDailyRefresh
        val due = since >= 6 * 60 * 60 * 1000L ||
            (dailyFailed && (networkCameUp || since >= 10 * 60 * 1000L)) ||
            (!hasTodaysDaily() && since >= DAILY_RETRY_MS)
        if (due) scope.launch { refreshDaily() }
    }

    /** Bing's dates are China's, as yyyyMMdd. */
    private fun hasTodaysDaily(): Boolean {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        return readDailyIndex().any { it.first >= today }
    }

    private var dailyRetry: Job? = null

    /** Looks again in half an hour while today's photo is not out; Home may stay up all night. */
    private fun retryDailyLater() {
        dailyRetry?.cancel()
        if (!dailyEnabled() || hasTodaysDaily()) return
        dailyRetry = scope.launch {
            kotlinx.coroutines.delay(DAILY_RETRY_MS)
            refreshDailyIfStale()
        }
    }

    suspend fun refreshDaily() = io.withLock {
        // A last guard for a fetch started just before the daily photo went out of use.
        if (!dailyEnabled()) return@withLock
        lastDailyRefresh = System.currentTimeMillis()
        val added = withContext(Dispatchers.IO) {
            runCatching {
                // The market sets the language of the captions; Bing has no Russian or Ukrainian ones
                // (it answers those markets in English), so everything but Chinese asks for English.
                val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
                // Each market has its own photo for a given date, and the files are named by date.
                val marketFile = File(dailyDir, "market")
                // No file: kept before markets were told apart, which was always zh-CN.
                val before = marketFile.takeIf { it.exists() }?.readText() ?: "zh-CN"
                // One photo only (owner, 2026-11-12): Bing is asked for the latest alone, and the
                // eight it used to list are no longer all downloaded and kept.
                val json = JSONObject(get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=$market").decodeToString())
                val img = json.getJSONArray("images").getJSONObject(0)
                val date = img.getString("startdate")
                val file = File(dailyDir, "$date.jpg")
                // A changed market has a different picture for the same date, so it is fetched again.
                // What is on screen stays until the new one is here and decodes (issue #129).
                if (before != market || !file.exists()) {
                    // url is the 1920×1080 picture; a UI drawn above 1080p gets the UHD one, and
                    // so does a portrait one, which shows only its middle, 1080 px tall in the small one.
                    val path = if (screenW > 1920 || screenH > 1080) img.getString("urlbase") + "_UHD.jpg" else img.getString("url")
                    val part = File(dailyDir, "$date.part")
                    try {
                        part.writeBytes(get("https://cn.bing.com" + path))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(part.path, bounds)
                        check(bounds.outWidth > 0) { "not a picture" }
                        // The new picture is ready: only now may the old one be replaced.
                        if (file.exists()) file.delete()
                        check(part.renameTo(file)) { "could not move it in" }
                    } finally {
                        part.delete()
                    }
                }
                if (before != market || !marketFile.exists()) marketFile.writeText(market)
                val copyright = img.optString("copyright")
                val title = img.optString("title").ifBlank { copyright.substringBefore(" (").ifBlank { date } }
                val index = JSONArray().put(JSONObject().put("date", date).put("title", title).put("caption", copyright))
                File(dailyDir, "index.json").writeText(index.toString())
                // The new one is in place: every other photo goes at once, so only one is kept.
                dailyDir.listFiles { f -> f.extension == "jpg" && f.name != "$date.jpg" }?.forEach { it.delete() }
                true
            }.onFailure { Log.w(TAG, "daily photo unavailable", it) }.isSuccess.also { dailyFailed = !it }
        }
        retryDailyLater()
        if (added) {
            synchronized(thumbs) { thumbs.remove(LATEST_DAILY) }
            rebuildList()
            // The old dates have just gone from disk: retarget the tiles that named them now,
            // rather than leaving them broken until the next cold start.
            sanitizePerApp()
            // A tile that chose "Daily Photo" had nothing to bake until this download landed.
            // Bake it now, and again whenever the photo changes from one day to the next.
            if (selectedId == PER_APP) {
                val dailySlots = perApp.filterValues { it == LATEST_DAILY || it.startsWith("daily:") }.keys.toList()
                if (dailySlots.isNotEmpty()) preparePerApp(dailySlots, force = true)
            }
            // A wallpaper pointing at yesterday's photo must not be left without a picture.
            if (selectedId != LATEST_DAILY && selectedId.startsWith("daily:") && items.none { it.id == selectedId }) {
                selectedId = LATEST_DAILY
                prefs.edit { putString("selected", LATEST_DAILY) }
            }
            if (selectedId.startsWith("daily:")) rebuildBackdrop()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    // ---- uploads ----

    /** Stores an uploaded picture, downscaled to at most 2560 px wide, and returns its id. */
    suspend fun addPhoto(input: InputStream): String? = io.withLock {
        val id = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("upload", ".bin", app.cacheDir)
            try {
                tmp.outputStream().use { input.copyTo(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                if (bounds.outWidth <= 0) return@withContext null
                // Stored upright: the JPEG written below keeps no EXIF, so the turn is applied now.
                val orientation = Images.exifOrientation(tmp)
                val wide = if (Images.quarterTurn(orientation)) bounds.outHeight else bounds.outWidth
                var sample = 1
                while (wide / (sample * 2) >= 2560) sample *= 2
                val decoded = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                val bmp = Images.upright(decoded, orientation)
                val name = System.currentTimeMillis().toString(36)
                File(photoDir, "$name.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                "photo:$name"
            } finally {
                tmp.delete()
            }
        }
        if (id != null) {
            synchronized(thumbs) { thumbs.remove(ROTATE_PHOTOS) }
            rebuildList()
            // Not selected here: an upload joins the photos, and the wallpaper the owner chose
            // stays (the owner, 2026-11-04 — an upload used to take the wallpaper's place).
        }
        id
    }

    fun deletePhoto(id: String) {
        // Only photos that are listed: the id comes from the phone page, and "photo:../daily/..."
        // would otherwise reach other files (#57).
        val photo = items.firstOrNull { it.id == id && it.kind == WallpaperKind.Photo } ?: return
        fileFor(photo)?.delete()
        synchronized(thumbs) { thumbs.remove(id); thumbs.remove(ROTATE_PHOTOS) }
        rebuildList()
        sanitizePerApp()
        if (selectedId == id) select(LATEST_DAILY)
        else if (selectedId == ROTATE_PHOTOS) rebuildBackdrop()
    }
}
