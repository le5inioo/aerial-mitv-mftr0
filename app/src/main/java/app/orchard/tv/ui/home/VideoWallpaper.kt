package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.ScreensaverVideos
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The owner's own videos behind Home (issues #410, #583), sent from the phone page as for the video
 * screensaver: silent, filling the screen, one after another or just the one chosen on a loop (#762).
 * They play all the while Home shows them: a key does not stop them, and neither does scrolling the
 * grid (the owner, 2026-11-04 — stopping on the second row and starting again on the way back left
 * the screen black between the two). A panel over Home holds them too (Personalize, Control Center).
 * Let go only when the screensaver takes over or Home goes behind an app.
 */
@Composable
fun VideoWallpaper(
    moving: Boolean,
    /** False once the screensaver is up or Home is behind an app: the decoder goes at once. */
    keep: Boolean,
    /** A panel is over Home (Personalize, Control Center): the video goes on playing under it. */
    hold: Boolean = false,
    cover: Animatable<Float, *>,
    /** Which films to play: the wallpaper's own, or one "Each App" tile's choice. */
    filmsId: String = Wallpapers.selectedId,
    /**
     * Whether the frame it stopped on is copied as the picture on the way out. False for a tile,
     * which has a picture of its own: the copy waits on the main thread (copyFrameNow, up to
     * 250 ms) and read as a stutter on every tile stepped past (the owner, 2026-11-06).
     */
    copyOnLeave: Boolean = true,
    /** When a key was last pressed (uptimeMillis): frame copies wait for quiet while the UI moves. */
    lastInputAt: () -> Long = { 0L },
) {
    val context = LocalContext.current
    val lastInputNow by rememberUpdatedState(lastInputAt)
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    VideoSpeed(player)
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var showing by remember { mutableStateOf(false) }
    // A frame of the film being played has been drawn: what the cover waits for before it fades
    // the picture away, and what a switch to another film's wallpaper sets again.
    var rendered by remember { mutableStateOf(false) }
    // See LiveWallpaper: the surface disappears under standby / the lock screen, leaving a black
    // hole in the window. Hold the still until the new surface has rendered its first frame.
    var surfaceFramed by remember { mutableStateOf(false) }
    var surfaceLost by remember { mutableStateOf(0) }
    val play = moving || hold
    val keepNow by rememberUpdatedState(keep)
    val scope = rememberCoroutineScope()
    // Bumped whenever the player is let go: with the video set to keep moving (or a panel holding
    // it) play stays true, and without this the effect never ran again to open a new player, and
    // the wallpaper stayed on the last frame for good.
    var released by remember { mutableStateOf(0) }

    fun release() {
        // Left the "My Videos, In Turn" wallpaper: the next video becomes the held one, looping from
        // then on (owner, 2026-11-13). A single chosen video (video:<id>) keeps its own place.
        if (filmsId == Wallpapers.VIDEOS) Wallpapers.advanceRollingVideo()
        player?.release()
        player = null
        showing = false
        rendered = false
        surfaceFramed = false
        Wallpapers.dropFilmFrost()
        released++
    }

    suspend fun open(): ExoPlayer? {
        // The carousel ("My Videos, In Turn") holds one video and loops it; the next is warmed on
        // leaving (Wallpapers.advanceRollingVideo). A single chosen video (video:<id>) plays as is.
        val films = withContext(Dispatchers.IO) {
            if (filmsId == Wallpapers.VIDEOS) listOfNotNull(Wallpapers.rollingVideo())
            else Wallpapers.videoFilms(filmsId)
        }
        if (films.isEmpty()) return null
        // Where the picture on screen came from, so moving again does not cut to another place.
        val spot = Wallpapers.videoSpot()
        val first = films.indexOfFirst { it.id == spot?.first }
        rendered = false
        return ExoPlayer.Builder(context).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ALL
            // Filled to the edges whatever its shape: a wallpaper has no bars.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            // Home is drawn over it: the display keeps its rate (see LiveWallpaper).
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    surfaceFramed = true
                    showing = isPlaying
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                // Playing them in turn where the glass cannot be read off the video: the picture under
                // it stayed the first film's, and so did the glass of the plate and Control Center
                // through every film after it (TCL MT9655, issue #1158). Each film's poster now.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: return
                    scope.launch {
                        val clip = "${Wallpapers.selectedId}/$id"
                        // A new film, whether the carousel rolled on or another video wallpaper
                        // was chosen (PLAYLIST_CHANGED). Its poster is on disk and small, so the
                        // plate and the panels take its glass at once. Waiting for the next
                        // frame sample left the menu's background on the film before — for good
                        // on TVs where sampling was shut down as too costly (owner, 2026-11-12).
                        val small = withContext(Dispatchers.IO) { Wallpapers.videoPoster(id, 320, 180) }
                        if (small != null) Wallpapers.useFilmFrost(small, clip)
                        else if (!copyGlassPossible) Wallpapers.useStillFilmFrost()
                        // Where the video plane cannot be read, the poster is the picture too:
                        // without it the picture and glass stayed the first film's through every
                        // film after it (issue #1158 on 1.81, TCL MT9655).
                        if (!copyGlassPossible) {
                            val poster = withContext(Dispatchers.IO) { Wallpapers.videoPoster(id, Wallpapers.screenW, Wallpapers.screenH) }
                            if (poster != null) Wallpapers.useLiveFrame(poster, id, 0L)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("video wallpaper failed (${error.errorCodeName})")
                    // Behind an app or a screensaver the decoder is theirs: no retry.
                    if (!keepNow) return
                    // One film this TV cannot decode; the others may still play.
                    if (mediaItemCount > 1) {
                        removeMediaItem(currentMediaItemIndex)
                        prepare()
                    }
                }
            })
            setMediaItems(
                films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
                first.coerceAtLeast(0),
                if (first >= 0) spot?.second ?: 0L else 0L,
            )
            surface?.let { setVideoSurfaceView(it) }
            prepare()
        }
    }

    // The plate's glass over the moving video, where PixelCopy reads it. Not from the file: an
    // owner's video may be 4K HEVC, and a second decoder beside the player's may not be there.
    LaunchedEffect(play, showing) {
        if (!play || !showing) return@LaunchedEffect
        sampleGlass({ player }, canTake = { copyGlassPossible },
            clip = { player?.currentMediaItem?.mediaId?.let { "${Wallpapers.selectedId}/$it" } },
            busy = { android.os.SystemClock.uptimeMillis() - lastInputNow() < GLASS_BUSY_MS }) { surface?.let { copyGlass(it) } }
    }

    // Let go as Aerial stops, not at the next frame, which does not come then (rememberStops).
    val stops = rememberStops {
        if (player == null) return@rememberStops false
        DiagLog.i("video wallpaper lets go as Aerial stops")
        release()
        scope.launch { cover.snapTo(0f) }
        true
    }

    LaunchedEffect(play, stops, released) {
        if (play) {
            val p = player ?: open()?.also { player = it } ?: return@LaunchedEffect
            DiagLog.i("video wallpaper starts (${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms)")
            Wallpapers.dropFilmFrost()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            snapshotFlow { showing }.first { it }
            cover.animateTo(1f, tween(1200, easing = Motion.EaseOut))
        } else {
            val p = player ?: return@LaunchedEffect
            if (!keepNow) {
                cover.snapTo(0f)
                release()
                return@LaunchedEffect
            }
            p.pause()
            DiagLog.i("video wallpaper stops at ${p.currentPosition} ms")
            if (cover.value > 0f) {
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val w = surface?.width?.takeIf { it > 0 } ?: Wallpapers.screenW
                val h = surface?.height?.takeIf { it > 0 } ?: Wallpapers.screenH
                // Neither on TCL MT9655 at times: then the film's own poster, so the picture and its
                // glass are at least the film that played, not the one shown before (issue #1158).
                // Not from the file where reading frames jams the media server (TCL's extractor): on
                // TCL MT5879 every stop read one, and the decoders failed to start or failed
                // mid-film after it, each time a stop came before (issue #1188, Aerial's own log).
                val frame = surface?.let { copyFrame(it) }
                    ?: film?.let { id ->
                        withContext(Dispatchers.IO) {
                            (if (fileFramesJam(context)) null else fileFrame(context, id, at, w, h)) ?: Wallpapers.videoPoster(id, w, h)
                        }
                    }
                if (frame != null && film != null) Wallpapers.useLiveFrame(frame, film, at)
                cover.animateTo(0f, tween(if (frame != null) 320 else 600, easing = Motion.EaseOut))
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) release()
    }
    // Another film's wallpaper on the same plane — the next tile's, or another video chosen. The
    // player and its surface stay: torn down and opened again, the window went black for the second
    // the new film took to show, on every tile stepped along the row (the owner, 2026-11-06).
    // The last frame stays on the surface while the new film opens, and the new film's first frame
    // takes over from it directly: a dip to the tile's poster in between read as a blink, and the
    // film coming back after it read as the old one left behind (the owner, 2026-11-06). Only a
    // frame slow to come (a cold file) has the poster stand in, for the while it takes.
    LaunchedEffect(filmsId) {
        val p = player ?: return@LaunchedEffect
        // The carousel holds one video and loops it; a single chosen video plays as is.
        val films = withContext(Dispatchers.IO) {
            if (filmsId == Wallpapers.VIDEOS) listOfNotNull(Wallpapers.rollingVideo())
            else Wallpapers.videoFilms(filmsId)
        }
        // The same films as now — two tiles taking one video: keep playing, do not even move.
        val now = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        if (now == films.map { it.id }) {
            DiagLog.i("video wallpaper keeps ${filmsId.takeLast(8)}")
            if (play) p.playWhenReady = true
            return@LaunchedEffect
        }
        DiagLog.i("video wallpaper switches to ${filmsId.takeLast(8)} (${films.size} films)")
        // Where the film on screen now was, for the tile it belongs to to go on from there when
        // it is back in focus.
        p.currentMediaItem?.mediaId?.let { Wallpapers.rememberVideoPosition(it, p.currentPosition) }
        if (films.isEmpty()) {
            cover.snapTo(0f)
            release()
            return@LaunchedEffect
        }
        rendered = false
        showing = false
        val spot = Wallpapers.videoSpot()
        val first = films.indexOfFirst { it.id == spot?.first }
        val start = first.coerceAtLeast(0)
        p.setMediaItems(
            films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
            start,
            if (first >= 0) spot?.second ?: 0L else Wallpapers.videoPosition(films[start].id),
        )
        p.prepare()
        if (play) {
            p.playWhenReady = true
            if (withTimeoutOrNull(SWITCH_FRAME_MS) { snapshotFlow { rendered }.first { it } } != true) {
                cover.snapTo(0f)
                snapshotFlow { rendered }.first { it }
            }
            if (cover.value < 0.99f) cover.animateTo(1f, tween(320, easing = Motion.EaseOut))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            DiagLog.i("video wallpaper view gone (${filmsId.takeLast(8)})")
            // Gone while it showed — the wallpaper changed away from the video, or Aerial stopped
            // over it: its last frame becomes the picture, so what follows fades in from where
            // the video was rather than cutting to the poster of an earlier moment (2026-11-04).
            // A copy that cannot be read here leaves the picture as it was. A tile keeps its own
            // picture and skips the copy, which waits on the main thread (the owner, 2026-11-06);
            // only where the film was is kept, for it to go on from when the tile is back.
            val frame = if (copyOnLeave && showing && cover.value > 0f) copyFrameNow(surface) else null
            if (frame != null && unreadable(frame) == null) {
                player?.currentMediaItem?.mediaId?.let { Wallpapers.leaveBehind(frame, it, player?.currentPosition ?: 0L, video = true) }
            } else if (!copyOnLeave) {
                player?.currentMediaItem?.mediaId?.let { Wallpapers.leaveVideoFilm(it, player?.currentPosition ?: 0L) }
            }
            release()
        }
    }

    // Standby or the lock screen takes the surface away and leaves a black hole in the window.
    // Keep the still over it until the first frame on the new surface, then fade the film back in
    // (see LiveWallpaper; owner, 2026-11-12).
    LaunchedEffect(surfaceLost) {
        if (surfaceLost == 0 || player == null || !keepNow) return@LaunchedEffect
        cover.snapTo(0f)
        DiagLog.i("video wallpaper: surface gone, the still holds until its first frame")
        val framed = withTimeoutOrNull(SURFACE_FRAME_WAIT_MS) {
            snapshotFlow { surfaceFramed }.first { it }
        }
        if (framed == null) DiagLog.i("video wallpaper: no first frame on the new surface; fading the still out anyway")
        if (!keepNow) return@LaunchedEffect
        cover.animateTo(1f, tween(SURFACE_FADE_MS, easing = Motion.EaseOut))
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FilmSlot(ctx, onSurfaceLost = { surfaceFramed = false; surfaceLost++ })
                .also { surface = it.surface; player?.setVideoSurfaceView(it.surface) }
        },
        onRelease = { player?.clearVideoSurfaceView(it.surface); surface = null },
    )
}

/** How long a switched-to film has to bring its first frame before the poster stands in. */
private const val SWITCH_FRAME_MS = 400L

/** Frame [atMs] of the owner's video [id], screen-sized and drawable on, or null. */
private fun fileFrame(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Bitmap? {
    val film = ScreensaverVideos.all(context).firstOrNull { it.id == id } ?: return null
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(film.file.path)
        val frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        app.orchard.tv.wallpaper.Images.cover(frame, w, h).let {
            if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
    } catch (e: Exception) {
        DiagLog.i("video wallpaper frame not decoded: ${e.javaClass.simpleName}")
        null
    } finally {
        runCatching { mmr.release() }
    }
}
