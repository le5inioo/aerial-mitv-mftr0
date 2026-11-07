package app.orchard.tv

import android.app.Application
import android.os.SystemClock
import app.orchard.tv.system.BootOpen

/**
 * The process's first look at the panel. Xiaomi's firmware stops Aerial on the way into standby
 * and starts a new process right behind the screen-off, but that process's activity only runs at
 * the light-up (measured 2026-11-07: started 23:57:42, its onCreate 23:57:47, the panel lit at
 * 47.3) — by then the screen is on, and the start-in-standby arm at the activity level sees the
 * panel lit and does nothing. The one moment this process can see the dark is here, at the process
 * start: arm the wake splash then. A cold power-on starts its process inside the first minute,
 * and boots owe no splash, so those are left out.
 */
class AerialApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SystemClock.elapsedRealtime() >= COLD_BOOT_MS) BootOpen.noteStartedInStandby(this)
    }

    private companion object {
        /** [MainActivity.WAKE_MIN_UPTIME_MS]: a start this early is the cold power-on itself. */
        const val COLD_BOOT_MS = 60_000L
    }
}
