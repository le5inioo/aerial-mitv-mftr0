package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Five seconds of Aerial over Home when the TV wakes from standby (2026-11-05, the owner's
 * request): a black screen and the little spinner, ported from DouYin-TV's launch screen
 * (github.com/Jie009/DouYin-TV, MIT).
 *
 * Home is composed late, behind the splash, on purpose. Firmware standby kills Aerial's process
 * (measured on MiTV-MFTR0: a new pid every wake), so a wake is a cold start, and Home's first
 * composition plus the wallpaper's decoder took the main thread for the splash's first second
 * — 49 frames and then 40 frames dropped, the spinner jerking while Home came up beneath it.
 * So: the spinner alone for [SPINNER_MS] (nothing else composes, nothing else drops), then the
 * spinner eases out, Home composes while the splash still holds the screen, and the fade starts
 * once Home has laid out — the desktop eases in rather than cutting in, with no black gap.
 *
 * Only wakes play it. A real start — a cold power-on or the power menu's restart — keeps the
 * firmware's own boot animation, which covers the whole start, so nothing of Aerial's would show
 * there. Standby is not a boot at all: the panel lights straight into Home, which this fills.
 *
 * [trigger] counts wakes; 0 draws Home alone. Each new value replays the splash from the start.
 */
private const val SPINNER_IN_MS = 500
/** Four seconds of spinner, then [FADE_MS] of Home easing in behind it — no black in between. */
private const val SPINNER_MS = 4_000L
private const val SPINNER_OUT_MS = 300
private const val LATE_MS = 1_200L
private const val FADE_MS = 700

@Composable
fun WakeSplash(trigger: Int, content: @Composable () -> Unit) {
    // Parsed before any wake asks for it: loading at wake time was part of the stutter, and
    // Lottie's process-wide cache keeps it warm across the activity a wake recreates.
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.boot_spinner))
    // Remembered without the trigger: Home, once composed, stays composed through later wakes
    // (a process that survives standby shows the splash over a live Home, not instead of it).
    var showHome by remember { mutableStateOf(trigger <= 0) }
    if (trigger <= 0) {
        content()
        return
    }
    var homeLaid by remember { mutableStateOf(false) }
    var done by remember(trigger) { mutableStateOf(false) }
    val splash = remember(trigger) { Animatable(1f) }
    val spinner = remember(trigger) { Animatable(0f) }
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LaunchedEffect(trigger) {
        spinner.animateTo(1f, tween(SPINNER_IN_MS, easing = Motion.EaseOut))
        delay(SPINNER_MS - SPINNER_IN_MS)
        // The spinner leaves before Home arrives: whatever Home's start costs, it is spent while
        // the splash still holds the screen, and the fade carries Home in without a black gap.
        spinner.animateTo(0f, tween(SPINNER_OUT_MS, easing = Motion.EaseOut))
        showHome = true
        DiagLog.i("wake splash: home composing")
        // Home's first layout starts the fade; a slow start only delays it so far.
        val late = homeLaid || withTimeoutOrNull(LATE_MS) { snapshotFlow { homeLaid }.first { it } } == true
        DiagLog.i("wake splash: fade (home laid=${late})")
        splash.animateTo(0f, tween(FADE_MS, easing = Motion.EaseOut))
        done = true
    }
    Box(Modifier.fillMaxSize()) {
        if (showHome) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { if (!homeLaid && it.size.height > 0) homeLaid = true },
            ) { content() }
        }
        if (!done) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = splash.value }
                    .background(Palette.Background),
                contentAlignment = Alignment.Center,
            ) {
                LottieAnimation(
                    composition,
                    { progress },
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer { this.alpha = spinner.value },
                )
            }
        }
    }
}
