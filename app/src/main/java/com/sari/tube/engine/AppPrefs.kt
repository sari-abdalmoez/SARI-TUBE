package com.sari.tube.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** User settings for downloads, stored in this app's private SharedPreferences. */
object AppPrefs {
    /** How many times the clipboard reminder was shown for this link. The reminder stops after two. */
    fun clipboardReminders(c: Context, url: String): Int =
        c.getSharedPreferences("sari_tube_prefs", Context.MODE_PRIVATE).getInt("clip_" + url.hashCode(), 0)

    fun countClipboardReminder(c: Context, url: String) {
        c.getSharedPreferences("sari_tube_prefs", Context.MODE_PRIVATE).edit()
            .putInt("clip_" + url.hashCode(), clipboardReminders(c, url) + 1).apply()
    }

    private const val NAME = "sari_tube_prefs"

    private fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Number of downloads that run at the same time (1 to 3). Others wait in the queue. */
    fun maxParallel(c: Context): Int = prefs(c).getInt("parallel", 2).coerceIn(1, 3)

    fun setMaxParallel(c: Context, value: Int) {
        prefs(c).edit().putInt("parallel", value.coerceIn(1, 3)).apply()
    }

    /** Speed cap in KB/s. 0 means unlimited. */
    fun speedKbps(c: Context): Int = prefs(c).getInt("speed_kbps", 0)

    fun setSpeedKbps(c: Context, value: Int) {
        prefs(c).edit().putInt("speed_kbps", value.coerceAtLeast(0)).apply()
        applyRate(c)
    }

    /** Whether downloads may start over mobile data. */
    fun allowMobileData(c: Context): Boolean =
        prefs(c).getBoolean("mobile_data", true)

    fun setAllowMobileData(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("mobile_data", value).apply()
    }

    /** True only when the active default network is Wi-Fi or Ethernet. */
    fun isOnWifiOrEthernet(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return false

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Pushes the saved speed cap into the downloader. Call at app start. */
    fun applyRate(c: Context) {
        RateLimiter.bytesPerSec = speedKbps(c) * 1024L
    }
}

/** One speed cap shared by all active downloads. Approximate: enforced per one-second window. */
object RateLimiter {
    private const val WINDOW_NS = 1_000_000_000L

    @Volatile var bytesPerSec: Long = 0L

    private val lock = Any()
    private var windowStart = System.nanoTime()
    private var windowBytes = 0L

    fun consume(n: Int) {
        val limit = bytesPerSec
        if (limit <= 0L || n <= 0) return
        var sleepNs = 0L
        synchronized(lock) {
            val now = System.nanoTime()
            if (now - windowStart >= WINDOW_NS) {
                windowStart = now
                windowBytes = 0L
            }
            windowBytes += n
            if (windowBytes > limit) sleepNs = windowStart + WINDOW_NS - now
        }
        if (sleepNs > 0L) Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
    }
}
