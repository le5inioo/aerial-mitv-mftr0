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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay

/**
 * Three seconds of Aerial over Home when the TV wakes from standby (2026-11-05, the owner's
 * request): a black screen and the little spinner, ported from DouYin-TV's launch screen
 * (github.com/Jie009/DouYin-TV, MIT).
 *
 * Only wakes play it. A real start — a cold power-on or the power menu's restart — keeps the
 * firmware's own boot animation, which covers the whole start (measured on MiTV-MFTR0: Home is
 * up 13 s in, that animation leaves at 15-17 s), so nothing of Aerial's would show there.
 * Standby is not a boot at all: the panel lights straight into Home, which is what this fills.
 *
 * [trigger] counts wakes; 0 draws nothing. Each new value replays the splash from the start.
 */
private const val SPLASH_MS = 3_000L
private const val FADE_MS = 350

@Composable
fun WakeSplash(trigger: Int) {
    if (trigger <= 0) return
    var show by remember(trigger) { mutableStateOf(true) }
    val alpha = remember(trigger) { Animatable(1f) }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.boot_spinner))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LaunchedEffect(trigger) {
        delay(SPLASH_MS)
        alpha.animateTo(0f, tween(FADE_MS, easing = Motion.EaseOut))
        show = false
    }
    if (!show) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Palette.Background),
        contentAlignment = Alignment.Center,
    ) {
        LottieAnimation(composition, { progress }, modifier = Modifier.size(96.dp))
    }
}
