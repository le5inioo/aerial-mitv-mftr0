package app.orchard.tv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.settings.AerialSettings

/**
 * Keeps [player] at the speed videos play at (AerialSettings.videoSpeed), and follows the slider
 * while it moves. Every player that shows a wallpaper or a screensaver calls this — the aerial
 * film behind Home, the owner's videos behind Home, and both screensavers — so one setting moves
 * them all, and one that starts later begins at the speed chosen.
 */
@Composable
internal fun VideoSpeed(player: ExoPlayer?) {
    val speed = AerialSettings.videoSpeed
    LaunchedEffect(player, speed) {
        player?.playbackParameters = PlaybackParameters(speed)
    }
}
