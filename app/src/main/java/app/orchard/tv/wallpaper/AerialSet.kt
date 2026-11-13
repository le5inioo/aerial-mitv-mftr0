package app.orchard.tv.wallpaper

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The one aerial film Aerial plays, kept on disk (AerialStream), shared by the aerial wallpaper and
 * every screensaver of aerials in every format, so going from one to the other is the same film
 * (owner, 2026-10-29: "从杜比的屏保到航拍壁纸切换的时候，最好是同一个视频").
 *
 * - One film at a time (owner, 2026-11-12). The wallpaper and the screensaver loop it; where it is
 *   and how far in is Wallpapers.liveSpot, written by whichever plays.
 * - It rolls forward one film each time the wallpaper or screensaver is left ([advance], owner,
 *   2026-11-13): the film on screen is the held one, and on leaving the next is warmed (its index
 *   and opening seconds fetched to disk) and becomes the held one, so showing it needs no network
 *   and only one film's bytes are ever on the TV. A right press in the screensaver ([addNew]) still
 *   brings a new film by hand.
 *
 * Until 1.69 every screensaver shuffled all 114 films, and the wallpaper picked its own: the two
 * rarely matched, and every film was fetched again each time (issue #843). A few were then kept
 * (1.70-1.94); the owner asked for just one again, to save space on the TV.
 */
object AerialSet {
    const val MAX = 1

    private const val PREFS = "aerial_set"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(context: Context): List<String> =
        prefs(context).getString("films", null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun save(context: Context, ids: List<String>, day: String? = null) = prefs(context).edit {
        putString("films", ids.joinToString(","))
        if (day != null) putString("day", day)
    }

    /** The set, newest first; on the first use, the film the wallpaper last showed, else any one. */
    @Synchronized
    fun films(context: Context): List<Aerial> {
        val all = Aerials.all(context)
        val known = ids(context).mapNotNull { id -> all.firstOrNull { it.id == id } }
        // A set saved by a build that kept three: only its newest is played now.
        if (known.isNotEmpty()) return known.take(MAX)
        val first = runCatching { Wallpapers.liveSpot() }.getOrNull()?.first?.let { id -> all.firstOrNull { it.id == id } } ?: all.random()
        save(context, listOf(first.id), today())
        return listOf(first)
    }

    /**
     * The films in playing order from [current]: it first, then those after it in the set, then
     * round to those before. A film not in the set (the wallpaper's from before 1.70) joins it.
     */
    @Synchronized
    fun playlist(context: Context, current: String?): List<Aerial> {
        var set = films(context)
        val all = Aerials.all(context)
        if (current != null && set.none { it.id == current }) {
            all.firstOrNull { it.id == current }?.let { film ->
                set = (listOf(film) + set).take(MAX)
                save(context, set.map { it.id })
            }
        }
        val at = set.indexOfFirst { it.id == current }.coerceAtLeast(0)
        return set.drop(at) + set.take(at)
    }

    /** The film a new day brings in, chosen ahead so the screensaver's start can be fetched first. */
    @Synchronized
    fun nextNew(context: Context): Aerial {
        val all = Aerials.all(context)
        prefs(context).getString("next", null)?.let { id -> all.firstOrNull { it.id == id } }?.let { return it }
        val kept = films(context).map { it.id }.toSet()
        val film = all.filter { it.id !in kept }.randomOrNull() ?: all.random()
        prefs(context).edit { putString("next", film.id) }
        return film
    }

    /** A film joins the set as its newest, the oldest leaves, and the next new one is chosen afresh. */
    @Synchronized
    fun addNew(context: Context): Aerial {
        val film = nextNew(context)
        val set = (listOf(film.id) + ids(context).filter { it != film.id }).take(MAX)
        save(context, set, today())
        prefs(context).edit { remove("next") }
        app.orchard.tv.system.DiagLog.i("aerial films now ${set.joinToString(",") { it.take(8) }}")
        return film
    }

    /** Whether today has not had its new film yet. Kept for the screensaver's start, which no longer turns the film over by the day. */
    fun due(context: Context): Boolean = prefs(context).getString("day", null) != today()

    /**
     * The film after [id] in the catalogue, cyclically: a fixed order the wallpaper and screensaver
     * both follow, so the same next film comes round wherever it is left (owner, 2026-11-13).
     */
    @Synchronized
    private fun nextCyclic(context: Context, id: String?): Aerial {
        val all = Aerials.all(context)
        check(all.isNotEmpty()) { "no aerials" }
        val at = if (id != null) all.indexOfFirst { it.id == id }.coerceAtLeast(0) else -1
        return all[(at + 1) % all.size]
    }

    /**
     * Rolls the held film on to the next one and warms it while idle, so the next look plays from
     * disk (owner, 2026-11-13). Called when the wallpaper or the screensaver is left: the held film
     * is the one just shown, the warmed one is the next. The old film's cache goes as the new one
     * begins to arrive (AerialEvictor).
     */
    @Synchronized
    fun advance(context: Context) {
        val current = films(context).firstOrNull() ?: return
        val next = nextCyclic(context, current.id)
        save(context, listOf(next.id), today())
        prefs(context).edit { remove("next") }
        DiagLog.i("aerial film rolls on to ${next.id.take(8)}")
        warm(context, next)
    }

    /** Warms the held film's index and opening seconds while it is idle, so showing it needs no network. */
    @Synchronized
    fun prepareCurrent(context: Context) {
        val current = films(context).firstOrNull() ?: return
        warm(context, current)
    }

    /** Fetches the film's index and opening seconds to disk, in the wallpaper's format and, where it
     * differs, the screensaver's, so both start from disk; nothing is fetched once aerials are off. */
    private fun warm(context: Context, film: Aerial) {
        val quality = AerialFormats.forWallpaper(context)
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        scope.launch(Dispatchers.IO) {
            AerialStream.prepareSeek(context, film, quality, overHttp)
            val sq = AerialFormats.forScreensaver(context, overWallpaper = true)
            if (sq != quality) AerialStream.prepareSeek(context, film, sq, overHttp)
        }
    }

    /** Today's date; a test moves it on. */
    internal var today: () -> String = { LocalDate.now().toString() }
}
