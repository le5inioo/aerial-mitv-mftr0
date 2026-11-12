package app.orchard.tv.ui.wallpaper

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.ClockHours
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.settings.UiSounds
import app.orchard.tv.settings.Effects
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.components.smoothBorder
import app.orchard.tv.ui.components.smoothCut
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialSet
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.system.SystemScreensaver
import app.orchard.tv.wallpaper.Wallpaper
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hiddenCount: Int,
    /**
     * The tiles of Home's first row (package name, label), in the order Home shows them: "Each App"
     * gives each a picture, by its place there — at most [Wallpapers.PER_APP_SLOTS] of them.
     */
    tiles: List<Pair<String, String>> = emptyList(),
    onHiddenApps: () -> Unit,
    onHomeLock: (on: Boolean) -> Unit,
    onStartScreensaver: () -> Unit,
    onPin: (on: Boolean) -> Unit,
    /** Kids and Elders mode's setup (behind the PIN). */
    onKids: () -> Unit,
    /** The sheet is all the way in (true) or not: Home need not draw under it then ([SheetCovers]). */
    onSettled: (Boolean) -> Unit = {},
    /** Draw the closed sheet once, nearly clear, so that its first opening is not its first draw (HomeScreen). */
    warm: Boolean = false,
) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    // The tile whose picture is being chosen ("Each App"): its place and app, or null when closed.
    var picking by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // The screensaver video picker ("All, in turn" or one film on a loop), over the sheet.
    var saverPicking by remember { mutableStateOf(false) }
    // Home Guard and ADB kept on, as Android has them now: read again each time the sheet opens,
    // since both can be changed elsewhere (the system's accessibility page, ADB itself).
    var guardOn by remember { mutableStateOf(GuardSetup.guardEnabled(context)) }
    var keepAdbOn by remember { mutableStateOf(GuardSetup.keepAdb(context)) }
    // Developer mode put back after each boot, as the firmware sometimes forgets it (2026-11-04).
    var bootDevModeOn by remember { mutableStateOf(GuardSetup.bootDevMode(context)) }
    var wakeAnimOn by remember { mutableStateOf(AerialSettings.wakeAnimation) }

    BackHandler(enabled = visible, onBack = onDismiss)

    // The phone page listens all along (PhoneServer.listen); the address is read again each time,
    // as the TV's may have changed.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
        phoneUrl = url
        qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
    }

    // Guard and ADB may have been changed elsewhere since the sheet last opened.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        guardOn = GuardSetup.guardEnabled(context)
        keepAdbOn = GuardSetup.keepAdb(context)
        bootDevModeOn = GuardSetup.bootDevMode(context)
        wakeAnimOn = AerialSettings.wakeAnimation
    }

    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val settled = shown.isIdle && shown.currentState
    LaunchedEffect(settled) { onSettled(settled) }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
        // A tap above the sheet closes it, back to Control Center as Back does. Once the sheet is
        // in, the dimming stops where it starts: the sheet is opaque.
        Box(
            Modifier.fillMaxSize().dismissOnTap(onDismiss).drawBehind {
                val h = if (settled) size.height - SheetCovers.toPx() else size.height
                drawRect(Color.Black.copy(alpha = 0.25f), size = Size(size.width, h))
            },
        )
    }

    // The sheet as it opens, or (takeFocus false) drawn once nearly clear for the warm-up.
    val sheet: @Composable (takeFocus: Boolean) -> Unit = { takeFocus ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(SheetHeight)
                .consumeTaps()
                // Glass made from the wallpaper itself, opaque: Home is not blurred behind
                // this sheet, so the wallpaper above it stays sharp while you choose, and the
                // app grid must not show through.
                .liquidGlass(SheetCorner, bottomCorners = false)
                .padding(start = 40.dp, end = 32.dp, top = 24.dp),
        ) {
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            // How Home looks, in the order you see it: the picture behind everything, how Home
            // is laid out over it, what replaces it when idle, then the weather and the PIN.
            // Settings sit in tvOS-style grouped rows; each group's note follows the focused row.
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).latestBringIntoView(scroll)) {
                BasicText(stringResource(R.string.wp_title), style = Type.LargeTitle)
                Spacer(Modifier.height(16.dp))
                SectionTitle(R.string.wp_wallpaper)
                WallpaperRows(takeFocus)
                // "Each App": one row for each tile of the first row, by its place. Home's
                // background swaps as the focus moves along that row; a tile keeps its picture when
                // the app in it is changed, and one that falls to the second row (a narrower row)
                // keeps the row's last picture. Only while it is the wallpaper: otherwise the rows
                // would sit there changing nothing.
                if (Wallpapers.selectedId == Wallpapers.PER_APP) {
                    Group(stringResource(R.string.wp_per_app_hint)) { note ->
                        tiles.take(Wallpapers.PER_APP_SLOTS).forEachIndexed { i, (_, label) ->
                            PerAppRow(i + 1, label, note, first = i == 0) { picking = (i + 1) to label }
                        }
                    }
                }
                // The owner's videos, like the aerial films, play all the time: there is no "when
                // still" any more (the owner, 2026-11-04), so there is nothing to choose here.
                // A video as the wallpaper (an aerial film or the owner's own): how fast it plays.
                if (Wallpapers.selectedId == Wallpapers.LIVE || Wallpapers.isVideo()) {
                    val speedHint = stringResource(R.string.wp_speed_hint)
                    Group(speedHint) { note -> SpeedRow(note, speedHint, first = true) }
                }
                // Only for "My Photos, in turn": how often the next photo comes (issue #1314).
                if (Wallpapers.selectedId == Wallpapers.ROTATE_PHOTOS) Group { note ->
                    Choices(
                        R.string.st_photo_every, stringResource(R.string.st_photo_every_hint), note,
                        AerialSettings.PHOTO_MINUTES.map { m ->
                            m to when {
                                m >= AerialSettings.DAY_MINUTES -> stringResource(R.string.st_photo_every_day)
                                m >= 60 -> stringResource(R.string.kids_h, m / 60)
                                else -> stringResource(R.string.kids_m, m)
                            }
                        },
                        AerialSettings.photoMinutes, { AerialSettings.choosePhotoMinutes(it); Wallpapers.rotateIfDue() }, first = true,
                    )
                }

                Spacer(Modifier.height(6.dp))
                SectionTitle(R.string.st_home_layout)
                // What is on Home and where; then the clock; then how the remote feels.
                Group { note ->
                    Choices(
                        R.string.st_layout, stringResource(R.string.st_home_layout_hint), note,
                        listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                        AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome, first = true,
                    )
                    // Two rows show the second row whole; only one row has a peek to hide.
                    if (AerialSettings.oneRowHome) Choices(
                        R.string.st_peek, stringResource(R.string.st_peek_hint), note,
                        listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                        AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
                    )
                    Choices(
                        R.string.st_columns, stringResource(R.string.st_columns_hint), note,
                        AerialSettings.HOME_COLUMNS.map { it to it.toString() },
                        AerialSettings.homeColumns, AerialSettings::chooseHomeColumns,
                    )
                    Choices(
                        R.string.st_plate, stringResource(R.string.st_plate_hint), note,
                        listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                        AerialSettings.homePlate, AerialSettings::chooseHomePlate,
                    )
                    Choices(
                        R.string.st_names, stringResource(R.string.st_names_hint), note,
                        listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                        AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
                    )
                    // Apps taken off Home are kept with the rest of how Home looks.
                    SettingRow(R.string.st_hidden_apps, stringResource(R.string.st_hidden_apps_hint), note) {
                        ActionChip(
                            if (hiddenCount == 0) stringResource(R.string.st_hidden_none) else stringResource(R.string.st_hidden_count, hiddenCount),
                            Glyph.ChevronRight,
                            onHiddenApps,
                        )
                    }
                    Choices(
                        R.string.st_home_lock, stringResource(R.string.st_home_lock_hint), note,
                        listOf(false to R.string.st_off, true to R.string.st_on),
                        AerialSettings.homeLocked, onHomeLock,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Group { note ->
                    Choices(
                        R.string.st_clock, stringResource(R.string.st_clock_hint), note,
                        listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                        AerialSettings.clockRight, AerialSettings::chooseClockRight, first = true,
                    )
                    Choices(
                        R.string.st_clock_size, stringResource(R.string.st_clock_size_hint), note,
                        listOf(false to R.string.st_clock_small, true to R.string.st_clock_large),
                        AerialSettings.largeClock, AerialSettings::chooseLargeClock,
                    )
                    Choices(
                        R.string.st_clock_hours, stringResource(R.string.st_clock_hours_hint), note,
                        listOf(ClockHours.System to R.string.st_clock_hours_system, ClockHours.H24 to R.string.st_clock_hours_24, ClockHours.H12 to R.string.st_clock_hours_12),
                        AerialSettings.clockHours, AerialSettings::chooseClockHours,
                    )
                    Choices(
                        R.string.wp_weather, null, note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.showWeather, AerialSettings::chooseShowWeather,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Group { note ->
                    Choices(
                        R.string.st_focus_style,
                        stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                        note,
                        listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
                        AerialSettings.focusStyle, AerialSettings::chooseFocusStyle, first = true,
                    )
                    Choices(
                        R.string.st_sounds,
                        stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                        note,
                        listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
                        AerialSettings.uiSounds, { AerialSettings.chooseUiSounds(it); Sounds.prepare() },
                    )
                    Choices(
                        R.string.st_effects,
                        stringResource(
                            if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                            else R.string.st_effects_hint,
                        ),
                        note,
                        listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                        AerialSettings.effects, AerialSettings::chooseEffects,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionTitle(R.string.wp_screensaver)
                Group { note ->
                    ScreensaverKind(note, onStartScreensaver)
                    if (Wallpapers.screensaver == ScreensaverMode.FlipClock) Choices(
                        R.string.ss_seconds, stringResource(R.string.ss_seconds_hint), note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.flipClockSeconds, AerialSettings::chooseFlipClockSeconds,
                    )
                    // The videos screensaver plays them all in turn; one of them may be picked to
                    // loop instead, until another is picked or it is deleted. A second-level menu:
                    // the row opens the picker over the sheet.
                    if (Wallpapers.screensaver == ScreensaverMode.Videos) {
                        val films = remember(Wallpapers.items) {
                            Wallpapers.items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
                        }
                        if (films.isNotEmpty()) SettingRow(
                            R.string.ss_saver_video, stringResource(R.string.ss_saver_video_hint), note,
                        ) {
                            val chosenTitle = films.firstOrNull { it.id == Wallpapers.VIDEO_PREFIX + Wallpapers.screensaverVideo }?.title
                            ActionChip(
                                chosenTitle ?: stringResource(R.string.ss_saver_video_all),
                                Glyph.ChevronRight,
                            ) { saverPicking = true }
                        }
                    }
                    // Only the qualities this TV can play; a 1080p-only TV has nothing to choose.
                    val qualities = remember { AerialFormats.supported(context) }
                    // The screensaver is a video too (Apple's aerials or the owner's own): the same speed.
                    if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Videos) {
                        SpeedRow(note, stringResource(R.string.wp_speed_hint))
                    }
                    // The quality is the aerial films' own: it has nothing to say for the other
                    // saver kinds, so it appears only while the aerials are the chosen one.
                    if (qualities.size > 1 && Wallpapers.screensaver == ScreensaverMode.Aerial) {
                        // The films kept on the TV (AerialSet, AerialStream): how many, how much
                        // space at most, and how they change. In place of the quality's own note,
                        // as a note shows two lines at most.
                        val keeps = stringResource(
                            R.string.ss_aerial_keeps,
                            remember { android.text.format.Formatter.formatShortFileSize(context, AerialStream.keepLimit(context)) },
                            AerialSet.MAX,
                        )
                        Choices(
                            R.string.ss_quality, keeps, note,
                            listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                                listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                                    .filter { it.first in qualities },
                            AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
                        )
                    }
                    // The clock screensaver is its clock, and the others show none.
                    if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Photos) Choices(
                        R.string.ss_show_time, stringResource(R.string.ss_show_time_hint), note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock,
                    )
                    // The firmware's screensaver starts on the firmware's timer: Aerial's delay
                    // did nothing there, and Xiaomi's 1 minute came before the 5 chosen (issue #675).
                    if (Wallpapers.screensaver == ScreensaverMode.System) SettingRow(R.string.ss_delay, null, note) {
                        val delay = remember { SystemScreensaver.firmwareDelayMs(context) }
                        BasicText(
                            when {
                                delay == null -> stringResource(R.string.ss_delay_system)
                                delay == 0L -> stringResource(R.string.ss_delay_system_off)
                                else -> stringResource(R.string.ss_delay_system_minutes, ((delay + 30_000) / 60_000).coerceAtLeast(1).toInt())
                            },
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    } else Choices(
                        R.string.ss_delay, null, note,
                        AerialSettings.screensaverChoices.map {
                            it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
                        },
                        AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
                    )
                    Choices(
                        R.string.ss_back, stringResource(R.string.ss_back_choice_hint), note,
                        listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
                        AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionTitle(R.string.wp_weather)
                Group(stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)) { note ->
                    SettingRow(R.string.wp_weather_city, null, note, first = true) { WeatherValue() }
                }

                // One row says what it is; a title over it would only repeat it.
                Spacer(Modifier.height(10.dp))
                // No explicit scrolling here on focus, unlike before: the Group's own responder
                // already brings the whole group in, note included, and an extra request of our
                // own only fought it — to the sheet's absolute bottom once (the row then hidden
                // above whenever anything followed the group), and to the sheet's top with a
                // 1 dp anchor (2026-11-04). Every other group scrolls right without one.
                Group(stringResource(R.string.st_pin_hint)) { note ->
                    SettingRow(R.string.st_pin, null, note, first = true) {
                        PinChoice(onPin)
                    }
                    SettingRow(R.string.st_kids, stringResource(R.string.st_kids_hint), note) {
                        ActionChip(
                            when {
                                !Restricted.enabled -> stringResource(R.string.st_off)
                                Restricted.kind == RestrictedKind.Kids -> stringResource(R.string.kids_kind_kids)
                                else -> stringResource(R.string.kids_kind_elders)
                            },
                            Glyph.ChevronRight,
                            onKids,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                SectionTitle(R.string.st_tv_system)
                Group { note ->
                    // Both were only reachable over ADB once this build owned Home (the Setup page
                    // shows when it does not): the owner asked for them back, on the TV.
                    Choices(
                        R.string.st_guard,
                        stringResource(R.string.st_guard_hint),
                        note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        guardOn,
                        { on ->
                            // Written straight into Android's list, which needs WRITE_SECURE_SETTINGS
                            // (tools/install-ts.sh grants it). Without it, the system's page, where
                            // the owner can switch it by hand.
                            if (GuardSetup.setGuardEnabled(context, on)) guardOn = on
                            else SystemProbe.guardSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
                        },
                        first = true,
                    )
                    Choices(
                        R.string.st_keep_adb,
                        stringResource(R.string.st_keep_adb_hint),
                        note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        keepAdbOn,
                        { on -> GuardSetup.setKeepAdb(context, on); keepAdbOn = on },
                    )
                    // The firmware drops developer mode at some starts, and the ADB switch lives
                    // inside it: put all of it back when that is on (the owner, 2026-11-04).
                    Choices(
                        R.string.st_dev_mode,
                        stringResource(R.string.st_dev_mode_hint),
                        note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        bootDevModeOn,
                        { on -> GuardSetup.setBootDevMode(context, on); bootDevModeOn = on },
                    )
                    // Three seconds of spinner when the TV wakes from standby (2026-11-05).
                    Choices(
                        R.string.st_wake_anim,
                        stringResource(R.string.st_wake_anim_hint),
                        note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        wakeAnimOn,
                        { on -> AerialSettings.chooseWakeAnimation(on); wakeAnimOn = on },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.width(28.dp))
            PhoneCard(phoneUrl, qr)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // The first opening composed the sheet and gave the GPU its pictures, glyphs and shaders
        // for the first time: frames of 250 and 316 ms, 1.16 s for the slide-in, against about
        // 140 ms at worst on later openings (Xiaomi MiTV-MFTR0). Drawn once at 1% while Home is
        // idle, without focus (HomeScreen, as Control Center), the first opening is like the next.
        if (warm && !visible) Box(Modifier.graphicsLayer { alpha = 0.01f }) { sheet(false) }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            sheet(true)
        }
    }

    // Over the sheet: the pictures one of the first tiles may take.
    picking?.let { (slot, label) ->
        PerAppPicker(
            slot = slot,
            label = label,
            chosen = Wallpapers.perAppChoice(slot),
            onDismiss = { picking = null },
            onPick = { id ->
                Wallpapers.setPerApp(slot, id)
                // Choosing a picture from another wallpaper turns "Each App" on, so the choice
                // shows at once; "inherit" leaves the wallpaper as it is.
                if (id != null && Wallpapers.selectedId != Wallpapers.PER_APP) Wallpapers.select(Wallpapers.PER_APP)
                picking = null
            },
        )
    }

    // Over the sheet: which of the videos the screensaver plays — all of them in turn, or one.
    if (saverPicking) SaverVideoPicker(onDismiss = { saverPicking = false })
}

/**
 * Every wallpaper in one row, in the order most people want them: the moving aerial, Bing's photo
 * of the day, the owner's own photos, then a few of the built-in pictures and "Follow App". Past
 * daily photos and the rest of the built-ins are left out unless one of them is the current
 * choice. The focused card's note is under the row, as under a group of settings.
 */
@Composable
private fun WallpaperRows(takeFocus: Boolean) {
    val items = Wallpapers.items
    val selected = Wallpapers.selectedId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val dailyTitle = stringResource(R.string.wp_daily_latest)
    val rotateTitle = stringResource(R.string.wp_photos_rotate)
    val videosTitle = stringResource(R.string.wp_videos_rotate)
    val list = remember(items, selected, dailyTitle, rotateTitle, videosTitle) {
        buildList {
            addAll(items.filter { it.kind == WallpaperKind.Live })
            // "Each App" right after the aerial wallpaper. Not also further down, where the
            // built-ins are: it is one of those, and a card seen twice is a crash (LazyRow keys).
            items.firstOrNull { it.id == Wallpapers.PER_APP }?.let { add(it) }
            // The image host's picture before the owner's own (the owner, 2026-11-04).
            addAll(items.filter { it.kind == WallpaperKind.Webhost })
            // The owner's photos before their videos (the owner, 2026-11-04). With two or more
            // photos, one card takes turns through all of them, one a day (#27).
            val photos = items.filter { it.kind == WallpaperKind.Photo }
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, rotateTitle))
            addAll(photos)
            // Each video on its own, and with two or more one card that plays them in turn (#762).
            val videos = items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
            if (videos.size > 1 || selected == Wallpapers.VIDEOS) add(Wallpaper(Wallpapers.VIDEOS, WallpaperKind.Video, videosTitle))
            addAll(videos)
            if (items.any { it.kind == WallpaperKind.Daily }) add(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, dailyTitle))
            addAll(items.filter { it.kind == WallpaperKind.Daily && it.id == selected })
            addAll(items.filter { it.kind == WallpaperKind.Builtin && (it.id in Wallpapers.FEATURED_BUILTINS || (it.id == selected && it.id != Wallpapers.PER_APP)) })
            addAll(items.filter { it.kind == WallpaperKind.Auto })
            // The inputs' pictures last: a choice for a home with a set-top box, not the usual one.
            addAll(items.filter { it.kind == WallpaperKind.Source })
        }
    }
    var note by remember { mutableStateOf<String?>(null) }
    // A row clips to its bounds, and the focused card grows by 8%: room on the left for that,
    // with the row moved back by as much so the cards still line up with the titles.
    LazyRow(
        modifier = Modifier.offset(x = (-10).dp).drawWithContent {
            // Keep the cards out of the phone column on old GPUs (#557), independently of the
            // scroll layer's clip. Leave vertical room for the focused card's 8% zoom.
            val focusRoom = 16.dp.toPx()
            clipRect(top = -focusRoom, bottom = size.height + focusRoom) {
                this@drawWithContent.drawContent()
            }
        },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    ) {
        items(list, key = { it.id }) { wp ->
            val hint = wallpaperNote(wp)
            WallpaperCard(wp, if (wp === list.first()) first else null, onFocused = { note = hint })
        }
    }
    Box(Modifier.height(42.dp).padding(start = 4.dp, top = 8.dp)) {
        note?.let { BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** What a wallpaper card is, for the note under the row; null where its picture says it all. */
@Composable
private fun wallpaperNote(wp: Wallpaper): String? = when {
    wp.kind == WallpaperKind.Live -> stringResource(R.string.wp_live)
    wp.id == Wallpapers.VIDEOS -> stringResource(R.string.wp_videos_note)
    wp.kind == WallpaperKind.Video -> stringResource(R.string.wp_video_note)
    wp.kind == WallpaperKind.Source -> stringResource(R.string.wp_source_note)
    wp.id == Wallpapers.LATEST_DAILY -> stringResource(R.string.wp_daily_note)
    wp.id == Wallpapers.ROTATE_PHOTOS -> stringResource(R.string.wp_photos_rotate_note)
    wp.kind == WallpaperKind.Photo -> stringResource(R.string.wp_photo_note)
    wp.kind == WallpaperKind.Auto -> stringResource(R.string.wp_auto_note)
    wp.kind == WallpaperKind.Webhost -> stringResource(R.string.wp_webhost_note)
    wp.id == Wallpapers.PER_APP -> stringResource(R.string.wp_per_app_hint)
    wp.kind == WallpaperKind.Daily -> wp.caption
    else -> null
}

private val CardCorner = 12.dp

@Composable
private fun WallpaperCard(
    wp: Wallpaper,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    selected: Boolean = Wallpapers.selectedId == wp.id,
    onClick: () -> Unit = { Wallpapers.select(wp.id) },
) {
    // A moving wallpaper's card is made again when the frame it stopped on changes, also while the
    // sheet is open: the film stopped as the sheet opened is decoded seconds later (Xiaomi MiTV-MFTR0).
    val picture = if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) Wallpapers.pictureVersion else 0
    // The picture is cut to the card's continuous outline once, at the size it is drawn, so the
    // card is one image draw and nothing is clipped (see SmoothCorners.kt).
    val density = LocalDensity.current
    val cardW = with(density) { 168.dp.roundToPx() }
    val cardH = with(density) { 94.dp.roundToPx() }
    val corner = with(density) { CardCorner.toPx() }
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items, picture, cardW, cardH) {
        value = withContext(Dispatchers.Default) {
            Wallpapers.thumbnail(wp.id)?.let { smoothCut(it, cardW, cardH, corner).asImageBitmap() }
        }
    }
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Column(Modifier.width(168.dp)) {
            Box(
                Modifier
                    .size(168.dp, 94.dp)
                    .smoothBackground(if (thumb == null) Palette.Fill else Color.Transparent, CardCorner)
                    .smoothBorder(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        CardCorner,
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
                // The aerial wallpaper moves; its card shows the last frame it stopped on.
                if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) {
                    GlyphIcon(Glyph.Play, Color.White, 22.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
                // An input's picture cannot be copied for a card: its mark over the dark picture.
                if (wp.kind == WallpaperKind.Source) {
                    GlyphIcon(Glyph.Hdmi, Color.White, 34.dp, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    BasicText(stringResource(title), style = Type.Headline, modifier = Modifier.padding(start = 4.dp))
    Spacer(Modifier.height(10.dp))
}

/**
 * A card of setting rows, as in tvOS Settings, with one note under it: the focused row's, or
 * [note] when the row has none or focus is elsewhere. The note keeps two lines of room so the
 * sheet does not shift as focus moves.
 */
@Composable
private fun Group(note: String? = null, rows: @Composable ColumnScope.(note: (String?) -> Unit) -> Unit) {
    var focusedNote by remember { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(0f) }
    // A focused row asks the sheet to show it from its top down to the group's note: the
    // screensaver's group sits at the sheet's bottom, and showing the row alone (on TV, Compose
    // keeps a focused row about a third down) left the quality's note below the screen. One
    // request, so the sheet scrolls once; a second scroll after the focus's own made the sheet
    // jump back and forth on every press (TCL MT5879).
    val withNote = remember {
        @OptIn(ExperimentalFoundationApi::class)
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect) =
                Rect(localRect.left, localRect.top, localRect.right, maxOf(localRect.bottom, height))
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    @OptIn(ExperimentalFoundationApi::class)
    Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height.toFloat() }.bringIntoViewResponder(withNote)) {
        Column(
            Modifier
                .fillMaxWidth()
                .smoothBackground(Color.White.copy(alpha = 0.05f), 16.dp)
                .onFocusChanged { inside = it.hasFocus },
        ) { rows { focusedNote = it } }
        Box(Modifier.height(38.dp).padding(start = 16.dp, top = 7.dp)) {
            (if (inside) focusedNote ?: note else note)?.let {
                BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One row: the setting's name on the left, its control on the right, a hairline above all but the first. */
@Composable
private fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

/** A row of chips for one of a few values. */
@Composable
private fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
) {
    SettingRow(label, hint, note, first) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, text) ->
                Chip(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
            }
        }
    }
}

/**
 * How fast videos play (AerialSettings.videoSpeed): one setting for every video surface — the
 * aerial film and the owner's videos as the wallpaper, either as the screensaver — so the slider
 * is shown wherever one of them is in use. Focus the row, then Left and Right step it a tenth of
 * a speed between 0.5× and 1.5×; the change reaches the playing film at once (VideoSpeed).
 */
@Composable
private fun SpeedRow(note: (String?) -> Unit, hint: String, first: Boolean = false) {
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Pressable(
        onClick = {},
        focusedScale = 1f,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) {
                val next = AerialSettings.videoSpeed +
                    (if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1) * AerialSettings.VIDEO_SPEED_STEP
                val kept = next < AerialSettings.MIN_VIDEO_SPEED - 0.001f || next > AerialSettings.MAX_VIDEO_SPEED + 0.001f
                if (kept) Sounds.limit() else Sounds.step()
                AerialSettings.chooseVideoSpeed(next)
            }
            true
        },
    ) { state ->
        // Read here, not passed in: a step from the keys has the row and the film behind Home
        // move at once, without a round trip through the sheet's state.
        val atSpeed = AerialSettings.videoSpeed
        val focused = state.focused
        val fg = if (focused) Palette.OnFocus else Palette.Label
        val track by animateColorAsState(if (focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "speed-bg")
        // The pill turns near-white under focus, so the fill must turn with it (dark on light) or
        // white-on-white hides the notch — the owner, 2026-11-05.
        val fill by animateColorAsState(if (focused) Palette.OnFocus else Color.White, tween(140, easing = Motion.EaseOut), label = "speed-fill")
        val fraction by animateFloatAsState(
            ((atSpeed - AerialSettings.MIN_VIDEO_SPEED) / (AerialSettings.MAX_VIDEO_SPEED - AerialSettings.MIN_VIDEO_SPEED)).coerceIn(0f, 1f),
            spring(dampingRatio = 1f, stiffness = 700f),
            label = "speed",
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 16.dp)
                .onFocusChanged { if (it.hasFocus) note(hint) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(stringResource(R.string.wp_speed), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
            Row(
                Modifier.smoothBackground(track, 14.dp).height(40.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Canvas(Modifier.size(width = 180.dp, height = 6.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(fill.copy(alpha = 0.3f), cornerRadius = r)
                    drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
                }
                BasicText(
                    String.format(java.util.Locale.US, "%.1f×", atSpeed),
                    style = Type.Body.copy(color = fg),
                    modifier = Modifier.width(46.dp),
                )
            }
        }
    }
}

/** Parental PIN on or off; either way goes through the PIN pad (issue #10). */
@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit, onFocused: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to R.string.st_pin_off, true to R.string.st_pin_on).forEach { (on, label) ->
            Chip(stringResource(label), on = AerialSettings.pinSet == on, onFocused = onFocused) {
                if (AerialSettings.pinSet != on) onPin(on)
            }
        }
    }
}

/** Where the weather is for and what it is now. The city is set on the phone page. */
@Composable
private fun WeatherValue() {
    val w = Weather.now
    val place = Weather.place
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.Cloudy -> R.string.wx_cloudy
                WeatherKind.Fog -> R.string.wx_fog
                WeatherKind.Rain -> R.string.wx_rain
                WeatherKind.Snow -> R.string.wx_snow
                WeatherKind.Thunder -> R.string.wx_thunder
            },
        )
    }
    BasicText(
        if (w != null) listOfNotNull(place, stringResource(R.string.weather_temperature, w.celsius) + " $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
        style = Type.Body.copy(color = Palette.SecondaryLabel),
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
private fun Chip(
    label: String, on: Boolean, onFocused: (() -> Unit)? = null, pad: Dp = 20.dp, onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            // One line always: in a row that runs out of width a label used to wrap into a
            // vertical stack of single characters rather than being shortened.
            BasicText(label, style = Type.Body.copy(color = fg), maxLines = 1, softWrap = false)
        }
    }
}

/** A pill that does something rather than choosing a value: white when focused, a glyph after its label. */
@Composable
private fun ActionChip(label: String, glyph: Glyph, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
            GlyphIcon(glyph, fg, 18.dp)
        }
    }
}

/** The kinds of screensaver, one row of chips: the labels are kept short so all eight fit. */
@Composable
private fun ScreensaverKind(note: (String?) -> Unit, onStart: () -> Unit) {
    val kinds = listOf(
        ScreensaverMode.Aerial to R.string.ss_aerial,
        ScreensaverMode.Photos to R.string.ss_photos,
        ScreensaverMode.Clock to R.string.ss_clock,
        ScreensaverMode.FlipClock to R.string.ss_flipclock,
        ScreensaverMode.Videos to R.string.ss_videos,
        ScreensaverMode.Webhost to R.string.ss_webhost,
        ScreensaverMode.System to R.string.ss_system,
    )
    // Read here, in composition: the chips call note() from their own callbacks.
    val hint = when {
        Wallpapers.screensaver == ScreensaverMode.Videos -> R.string.ss_videos_hint
        Wallpapers.screensaver == ScreensaverMode.System -> R.string.ss_system_hint
        Wallpapers.screensaver == ScreensaverMode.FlipClock -> R.string.ss_flipclock_hint
        Wallpapers.screensaver == ScreensaverMode.Webhost -> R.string.ss_webhost_hint
        Wallpapers.screensaverFollows -> R.string.ss_follow_hint
        AerialSettings.backStartsScreensaver -> R.string.ss_back_hint
        else -> null
    }
    val hintText = hint?.let { stringResource(it) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) note(hintText) }
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        BasicText(stringResource(R.string.ss_kind), style = Type.Body)
        Spacer(Modifier.height(10.dp))
        // Tighter gaps than the other rows: eight chips have to fit on the one.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            kinds.forEach { (mode, label) ->
                Chip(
                    stringResource(label),
                    on = Wallpapers.screensaver == mode,
                    onFocused = { note(hintText) },
                    pad = 14.dp,
                ) { Wallpapers.chooseScreensaver(mode) }
            }
            // The action that tries the chosen one, at the end of the row.
            StartChip(onStart)
        }
    }
}

/** Starts the screensaver now, to see what it looks like. */
@Composable
private fun StartChip(onStart: () -> Unit) {
    Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(Glyph.Play, fg, 14.dp)
            BasicText(
                stringResource(R.string.ss_start), style = Type.Body.copy(color = fg),
                maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(220.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(176.dp).smoothBackground(Color.White, 18.dp).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            // The whole address, token and all, to type into a computer's browser.
            BasicText(url.removePrefix("http://"), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}

private val SheetHeight = 360.dp
private val SheetCorner = 30.dp

/** "Each App": one of Home's first tiles, by its place, with the picture it has now. */
@Composable
private fun PerAppRow(slot: Int, label: String, note: (String?) -> Unit, first: Boolean, onPick: () -> Unit) {
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged { if (it.hasFocus) note(null) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(R.string.wp_per_app_row, slot, label),
            style = Type.Body,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ActionChip(
            if (Wallpapers.perAppChoice(slot) == null) stringResource(R.string.wp_per_app_inherit)
            else stringResource(R.string.wp_per_app_change),
            Glyph.ChevronRight,
            onPick,
        )
    }
}

/**
 * The pictures one of Home's first tiles may take, the same spread the wallpaper row offers —
 * the aerial films, the owner's videos and the set-top inputs included — with the wallpaper
 * row's "Follow App" as its default in front, and "Follow Tile" itself left out. The default
 * bakes nothing: Home shows the artwork of the app the focus is on (the owner, 2026-11-12).
 * A tile shows a still for anything else: the bake takes a film's poster or last stopped
 * frame, an input's and the aerial's stand-in picture.
 */
@Composable
private fun PerAppPicker(slot: Int, label: String, chosen: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val rotateTitle = stringResource(R.string.wp_photos_rotate)
    val videosTitle = stringResource(R.string.wp_videos_rotate)
    val list = remember(Wallpapers.items) {
        val all = Wallpapers.items
        // The wallpaper row's order, "跟随磁贴" dropped: live films, the image host, My Photos
        // (and their rotation with two or more), My Videos (and each film), the day's photo,
        // the built-ins, the inputs last.
        buildList {
            addAll(all.filter { it.kind == WallpaperKind.Live })
            addAll(all.filter { it.kind == WallpaperKind.Webhost })
            val photos = all.filter { it.kind == WallpaperKind.Photo }
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, rotateTitle))
            addAll(photos)
            val videos = all.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
            if (videos.isNotEmpty()) add(Wallpaper(Wallpapers.VIDEOS, WallpaperKind.Video, videosTitle))
            addAll(videos)
            addAll(all.filter { it.kind == WallpaperKind.Daily })
            addAll(all.filter { it.kind == WallpaperKind.Builtin && it.id != Wallpapers.PER_APP })
            addAll(all.filter { it.kind == WallpaperKind.Source })
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .dismissOnTap(onDismiss)
            .consumeTaps(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(780.dp)
                .liquidGlass(24.dp)
                .padding(horizontal = 26.dp, vertical = 20.dp),
        ) {
            BasicText(stringResource(R.string.wp_per_app_title, slot), style = Type.Headline)
            Spacer(Modifier.height(2.dp))
            // Which app sits in that tile just now: the picture stays with the tile, not the app.
            BasicText(label, style = Type.Caption.copy(color = Palette.SecondaryLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            BasicText(
                stringResource(R.string.wp_per_app_pick_hint),
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            ) {
                item(key = "inherit") {
                    InheritCard(selected = chosen == null, focusRequester = first) { onPick(null) }
                }
                items(list, key = { it.id }) { wp ->
                    WallpaperCard(wp, null, onFocused = {}, selected = wp.id == chosen) { onPick(wp.id) }
                }
            }
        }
    }
}

/**
 * Which of the owner's videos the screensaver plays: all of them in turn, or one on a loop until
 * another is picked or it is deleted (VideosSaver falls back to all when the film is gone).
 */
@Composable
private fun SaverVideoPicker(onDismiss: () -> Unit) {
    val films = remember(Wallpapers.items) {
        Wallpapers.items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .dismissOnTap(onDismiss)
            .consumeTaps(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(780.dp)
                .liquidGlass(24.dp)
                .padding(horizontal = 26.dp, vertical = 20.dp),
        ) {
            BasicText(stringResource(R.string.ss_saver_video), style = Type.Headline)
            Spacer(Modifier.height(4.dp))
            BasicText(
                stringResource(R.string.ss_saver_video_hint),
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            ) {
                item(key = "all") {
                    InheritCard(
                        selected = Wallpapers.screensaverVideo == null,
                        focusRequester = first,
                        onClick = { Wallpapers.chooseScreensaverVideo(null); onDismiss() },
                        label = R.string.ss_saver_video_all,
                    )
                }
                items(films, key = { it.id }) { film ->
                    val id = film.id.removePrefix(Wallpapers.VIDEO_PREFIX)
                    WallpaperCard(film, null, onFocused = {}, selected = Wallpapers.screensaverVideo == id) {
                        Wallpapers.chooseScreensaverVideo(id); onDismiss()
                    }
                }
            }
        }
    }
}

/** The default card: the tile takes no picture of its own, so Home follows the focused app's artwork. */
@Composable
private fun InheritCard(selected: Boolean, focusRequester: FocusRequester?, label: Int = R.string.wp_per_app_inherit, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        Box(
            Modifier
                .width(168.dp)
                .height(94.dp)
                .smoothBackground(Palette.Fill, CardCorner)
                .smoothBorder(
                    if (state.focused || selected) 3.dp else 1.dp,
                    when {
                        state.focused -> Palette.Focus
                        selected -> Palette.Focus.copy(alpha = 0.55f)
                        else -> Palette.Hairline
                    },
                    CardCorner,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                stringResource(label),
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
        }
    }
}

/**
 * How much of the screen's bottom the settled sheet hides whole: its height less its rounded top
 * corners, under which Home shows. Home drew all of itself under the opaque sheet every frame,
 * three full-screen layers and the tiles that nobody sees (Xiaomi MiTV-MFTR0).
 */
val SheetCovers = SheetHeight - SheetCorner
