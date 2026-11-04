package app.orchard.tv.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import app.orchard.tv.system.DiagLog
import kotlin.math.abs

/**
 * Xiaomi's settings app hides the ADB switch again at every boot: its own boot receiver switches
 * ADB off (GuardSetup puts adb_enabled back) and turns the "developer mode" flag off unless it was
 * enabled in the last minutes — the flag whose row the ADB switch sits under. That flag lives in
 * the settings app's private preferences (com.xiaomi.mitv.settings, `development.xml`'s `show`),
 * reachable only through the tap flow on the About page's model row (AboutView); no settings key,
 * no broadcast reaches it. WRITE_SECURE_SETTINGS already showed the keys right — adb connected,
 * the switch still hidden — so the guard plays the taps: it opens the settings' About page, taps
 * the model row the way the remote's user would (the counter is the same one), and goes back
 * Home. A few seconds of the settings app on screen once a boot, and the ADB switch is back
 * (the owner, 2026-11-04).
 */
object DevModeTap {
    private const val TAG = "AerialGuard"
    private const val PREFS = "system"
    private const val KEY_DONE_BOOT = "dev_tap_boot"

    private const val SETTINGS_PKG = "com.xiaomi.mitv.settings"
    private const val ABOUT_ACTIVITY = "com.xiaomi.mitv.settings.entry.AboutActivity"
    private const val MODEL_ROW = "产品型号"

    /** The counter needs eight taps from fresh (six "N steps to go", one "1 step", one enables). */
    private const val TAPS = 10

    /** How often the model row is looked for while the page opens and scrolls to it. */
    private const val LOOKS = 30
    private const val LOOK_MS = 700L

    /** The About page is a heavy screen on a just-booted TV: give it time to draw before looking. */
    private const val OPEN_MS = 3_000L
    private const val TAP_GAP_MS = 500L

    /** Only worth it early: the boot receiver hid the flag, and nothing else will again. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** Wall clock at this boot; two boots never agree, one boot always does. */
    private fun bootNow(context: Context) = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun doneThisBoot(context: Context): Boolean {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DONE_BOOT, 0L)
        return abs(saved - bootNow(context)) <= 2 * 60 * 1000L
    }

    private fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DONE_BOOT, bootNow(context)).apply()
    }

    /** Whether the tap flow is still called for this boot: opted in, in time, on the right TV. */
    fun pending(context: Context): Boolean {
        if (!GuardSetup.bootDevMode(context)) return false
        if (doneThisBoot(context)) return false
        if (SystemClock.elapsedRealtime() > WINDOW_MS) return false
        val hasSettings = runCatching { context.packageManager.getPackageInfo(SETTINGS_PKG, 0) }.isSuccess
        return hasSettings
    }

    /**
     * Opens the About page and taps the model row. Runs on its own thread: the node tree is read
     * and scrolled until the row shows, whatever the page's layout does meanwhile. Whether or not
     * the row is found, the boot counts as done — the keys are already right, and retrying would
     * keep putting the settings app over whatever the viewer is doing.
     */
    fun tap(service: AccessibilityService) {
        markDone(service)
        Thread {
            // Twice at most: the settings process was seen killed for memory on a just-booted TV
            // (load average near 40), taking the About page with it before the row could be read.
            var tapped = false
            for (tryNo in 1..2) {
                if (tryNo > 1) Thread.sleep(5_000L)
                if (attempt(service)) {
                    tapped = true
                    break
                }
            }
            if (tapped) DiagLog.i("developer mode: tapped the About page's model row")
            else Log.w(TAG, "developer mode: the About page's model row never came up (front: ${service.rootInActiveWindow?.packageName})")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }.apply { isDaemon = true }.start()
    }

    /** One run of the About page's tap flow; true once the row was tapped through. */
    private fun attempt(service: AccessibilityService): Boolean {
        try {
            service.startActivity(
                Intent().setComponent(ComponentName(SETTINGS_PKG, ABOUT_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "developer mode: could not open the About page", e)
            return false
        }
        Thread.sleep(OPEN_MS)
        for (look in 1..LOOKS) {
            val row = modelRow(service)
            if (row != null) {
                // Same flow as the remote's: one tap per press, the counter does the rest.
                var presses = 0
                for (i in 1..TAPS) {
                    if (!press(service)) break
                    presses++
                    Thread.sleep(TAP_GAP_MS)
                }
                Log.i(TAG, "developer mode: model row pressed $presses times")
                return presses > 0
            }
            // Not in view yet: scroll the page down a step, then let it settle before looking
            // again. A just-booted TV draws each page slowly; looking in a tight loop only
            // burned through the looks before the page was there.
            scrollForward(service)
            Thread.sleep(LOOK_MS)
        }
        return false
    }

    /** One press on the model row: its clickable ancestor, or a tap where the row is. */
    private fun press(service: AccessibilityService): Boolean {
        val row = modelRow(service) ?: return false
        var clickable: AccessibilityNodeInfo? = row
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable != null) return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // Nothing clickable above it (the row handles the touch itself): tap its middle.
        val bounds = android.graphics.Rect().also { row.getBoundsInScreen(it) }
        return tap(service, bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** The model row's label node, found by text; null while it is off screen. */
    private fun modelRow(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName != SETTINGS_PKG) return null
        return root.findAccessibilityNodeInfosByText(MODEL_ROW).firstOrNull { it.text == MODEL_ROW }
    }

    /** One scroll down on whatever list is on screen; false when the page has none. */
    private fun scrollForward(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val node = queue.removeFirst()
            seen++
            if (node.isScrollable) return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        // A page that scrolls without saying so (the About page's own layout): swipe it.
        return swipeUp(service)
    }

    /** A finger's swipe up the middle of the screen, for lists the node tree calls static. */
    private fun swipeUp(service: AccessibilityService): Boolean {
        // Gestures came with Android 7.0; before that only the node tree's own scrolling exists,
        // and no Xiaomi TV this runs on is that old (minSdk 21 is for the Aerial profiles).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.75f)
            lineTo(x, metrics.heightPixels * 0.3f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** A single touch at a point, for rows the node tree says are not clickable themselves. */
    private fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
