package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * The flip-clock screensaver (chosen in Personalize): a card per digit, HH MM SS in the 24-hour
 * clock, in the style of FlipClock (github.com/objectivehtml/FlipClock) drawn in Compose rather
 * than that library, which is for web pages. Each second the card of the changing digit plays the
 * split-flap: its old top falls away to the hinge, then the new bottom unfolds over it.
 *
 * Black behind the cards, like the original, and the whole clock drifts a little every minute (as
 * ClockSaver's clock does) so nothing sits on one spot for hours.
 */

/** One top-fall-plus-bottom-rise cycle, in milliseconds. */
private const val FLIP_MS = 460

private val CardTop = Color(0xFFF3F3F1)
private val CardBottom = Color(0xFFE1E1DF)
private val DigitInk = Color(0xFF17181A)
private val HingeInk = Color(0xFF0A0A0B)

private val CardCorner = 12.dp
private val CardW = 110.dp
private val CardH = 156.dp

private val FlipDigitStyle = Type.Clock.copy(
    fontSize = 96.sp, color = DigitInk, letterSpacing = 0.sp, textAlign = TextAlign.Center,
)

@Composable
internal fun FlipClockSaver(initial: LocalDateTime) {
    val now by rememberSecondly(initial)
    val minute = now.withSecond(0).withNano(0)
    // A spot per minute near the centre, so a card boundary (the hinge line and edges) does not sit
    // on one pixel group for hours.
    val drift = remember(minute) {
        val rnd = Random(minute.hashCode())
        Offset((rnd.nextFloat() - 0.5f) * 44f, (rnd.nextFloat() - 0.5f) * 30f)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Row(
            Modifier.offset(drift.x.dp, drift.y.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val h = "%02d%02d%02d".format(now.hour, now.minute, now.second)
            FlipDigit(h[0]); FlipDigit(h[1]); Colons(); FlipDigit(h[2]); FlipDigit(h[3]); Colons(); FlipDigit(h[4]); FlipDigit(h[5])
        }
    }
}

/** The time, fresh every second: Home's `now` only ticks on the minute, and the flip clock shows seconds. */
@Composable
private fun rememberSecondly(initial: LocalDateTime): State<LocalDateTime> {
    val now = remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(200)
            val t = LocalDateTime.now()
            if (t.withNano(0) != now.value.withNano(0)) now.value = t
        }
    }
    return now
}

/** One digit card with the split-flap animation over it. */
@Composable
private fun FlipDigit(digit: Char) {
    var current by remember { mutableStateOf(digit) }
    var previous by remember { mutableStateOf(digit) }
    val flip = remember { Animatable(1f) } // 1 = settled, showing only the current digit
    LaunchedEffect(digit) {
        if (digit == current) return@LaunchedEffect
        previous = current
        current = digit
        flip.snapTo(0f)
        flip.animateTo(1f, tween(FLIP_MS, easing = LinearEasing))
    }
    val p = flip.value
    // First half: the old top falls to the hinge. Second half: the new bottom unfolds from it.
    val topRot = -90f * (p * 2f).coerceIn(0f, 1f)
    val bottomRot = 90f * (1f - ((p - 0.5f) * 2f).coerceIn(0f, 1f))
    // A Box puts every child at its top left, so the bottom half carries its own offset down.
    Box(Modifier.size(CardW, CardH)) {
        Half(current, top = true)                                    // the new top, revealed as the old one falls
        Half(previous, top = false)                                  // the old bottom, until the new one covers it
        if (p < 1f) Half(previous, top = true, rotate = topRot)      // the falling old top
        if (p > 0.5f) Half(current, top = false, rotate = bottomRot) // the unfolding new bottom
        // The hinge, over everything, as on the original.
        Box(
            Modifier.matchParentSize().drawBehind {
                val y = size.height / 2f
                val line = 1.5.dp.toPx()
                drawRect(HingeInk, topLeft = Offset(0f, y - line), size = Size(size.width, line * 2f))
            },
        )
    }
}

/**
 * Half a card: [digit]'s face as the wanted half of it. With [rotate] it is the moving flap,
 * pivoting at the hinge; at 0 it lies flat, at ±90 it is edge-on and gone.
 *
 * Drawn on a Canvas: the whole card (rounded rect and digit, centred on the full card) into a clip
 * of this half's bounds, the bottom half translated up first so both halves share one layout. The
 * halves used to be Boxes with the digit clipped by an offset face, but the bottom half's digit
 * never drew on the TV (Xiaomi MiTV-MFTR0): text placed above its parent's bounds was lost.
 */
@Composable
private fun Half(digit: Char, top: Boolean, rotate: Float = 0f) {
    val half = CardH / 2
    val measurer = rememberTextMeasurer()
    // Only a moving flap gets a layer; a layer on every static half cost blank cards on the TV.
    val flap = if (rotate == 0f) Modifier
    else Modifier.graphicsLayer {
        rotationX = rotate
        transformOrigin = TransformOrigin(0.5f, if (top) 1f else 0f)
    }
    Canvas(Modifier.offset(y = if (top) 0.dp else half).then(flap).size(CardW, half)) {
        clipRect(0f, 0f, size.width, size.height) {
            translate(0f, if (top) 0f else -size.height) { // the bottom draws from the card's top too
                val card = Size(CardW.toPx(), CardH.toPx())
                drawRoundRect(
                    color = if (top) CardTop else CardBottom,
                    size = card, // clipped to this half, so only the outer corners come out rounded
                    cornerRadius = CornerRadius(CardCorner.toPx()),
                )
                val laid = measurer.measure(digit.toString(), FlipDigitStyle, maxLines = 1)
                drawText(
                    laid,
                    topLeft = Offset(
                        (card.width - laid.size.width) / 2f,
                        (card.height - laid.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

/** The two dots between hour, minute and second. */
@Composable
private fun Colons() {
    Column(
        Modifier.height(CardH).padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Dot()
        Spacer(Modifier.height(22.dp))
        Dot()
    }
}

@Composable
private fun Dot() {
    Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(DigitInk))
}
