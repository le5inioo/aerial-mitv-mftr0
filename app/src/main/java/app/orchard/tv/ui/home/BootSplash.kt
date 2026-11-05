package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay

/**
 * The boot splash, over Home, once per boot (2026-11-05, the owner's request): a black screen, a
 * little spinner and a hint line, fading out once Home is behind it. Ported from DouYin-TV's
 * launch screen (github.com/Jie009/DouYin-TV, MIT): the same 11 KB Lottie (boot_spinner.json,
 * deliberately not a heavier film — Lottie rasterizes on the CPU, and this plays while Home's
 * first frame and the wallpaper are still finding their feet) and the same composition of it.
 *
 * Whether it plays at all: AerialSettings.bootSplashPending — the setting on, the kernel this
 * young, and no splash shown yet this boot. A restart in the afternoon plays nothing.
 */
private const val SPLASH_MS = 2_400L
private const val FADE_MS = 600

@Composable
fun BootSplash() {
    var show by remember {
        mutableStateOf(AerialSettings.bootSplashPending().also { DiagLog.i("boot splash pending=$it") })
    }
    if (!show) return
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        DiagLog.i("boot splash playing")
        AerialSettings.markBootSplashShown()
        delay(SPLASH_MS)
        alpha.animateTo(0f, tween(FADE_MS, easing = Motion.EaseOut))
        show = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Palette.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.boot_spinner))
            val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
            LottieAnimation(composition, { progress }, modifier = Modifier.size(96.dp))
            BasicText(
                stringResource(R.string.boot_hint),
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
        }
    }
}
