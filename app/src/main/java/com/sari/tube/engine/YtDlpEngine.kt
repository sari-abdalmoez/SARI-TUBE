package com.sari.tube.engine

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** يهيّئ yt-dlp مرة واحدة في خيط خلفي، ويتيح لأي جزء من التطبيق الانتظار حتى يصبح جاهزاً. */
object YtDlpEngine {
    private const val TAG = "SariTube"
    private val ready = CompletableDeferred<Boolean>()

    @Volatile
    var appContext: Context? = null
        private set

    fun initializeAsync(app: Application) {
        appContext = app.applicationContext
        Thread({
            val ok = try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp init failed", e)
                false
            }
            ready.complete(ok)
            if (ok) YtDlpUpdater.updateIfDue(app)
        }, "ytdlp-init").start()
    }

    /** ينتظر حتى 90 ثانية. يُرجع false إذا فشلت التهيئة أو انتهت المهلة. */
    suspend fun awaitReady(): Boolean = withTimeoutOrNull(90_000L) { ready.await() } ?: false
}

/**
 * يحدّث مستخرج يوتيوب تلقائياً مرة كل 24 ساعة، أو عند الطلب بعد فشل البحث.
 * الدالة تعمل على الشبكة وتستدعى من خيط خلفي فقط.
 */
object YtDlpUpdater {
    private const val PREFS = "ytdlp_update"
    private const val KEY_LAST = "last_check"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    fun updateIfDue(app: Context) {
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last >= INTERVAL_MS) forceUpdate(app)
    }

    /** يُرجع نصاً قصيراً للسجل. لا يرمي استثناءً. */
    fun forceUpdate(app: Context): String = try {
        val status = YoutubeDL.getInstance().updateYoutubeDL(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
        "update: $status"
    } catch (e: Exception) {
        Log.w("SariTube", "yt-dlp update failed", e)
        "update failed: ${e.message}"
    }
}
