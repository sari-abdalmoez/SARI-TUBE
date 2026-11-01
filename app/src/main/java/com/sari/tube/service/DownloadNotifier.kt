package com.sari.tube.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlin.math.log10
import kotlin.math.pow

class DownloadNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val FG_ID = 1
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"

        fun human(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val g = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.size - 1)
            return "%.1f %s".format(bytes / 1024.0.pow(g.toDouble()), units[g])
        }
    }

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "التحميلات", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "اكتمال التحميل", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun foreground(activeCount: Int): Notification =
        base(CH_PROGRESS)
            .setContentTitle("SARI TUBE")
            .setContentText("جارٍ تنفيذ $activeCount تحميل")
            .setOngoing(true)
            .build()

    fun progress(id: Int, title: String, downloaded: Long, total: Long, speed: Long, indeterminate: Boolean = false) {
        val cancel = PendingIntent.getService(
            context, id,
            Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra(DownloadService.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (indeterminate) "جارٍ التحضير…"
        else "${human(downloaded)} / ${if (total > 0) human(total) else "؟"}  •  ${human(speed)}/ث"
        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        nm.notify(
            id,
            base(CH_PROGRESS)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setProgress(100, pct, indeterminate || total <= 0)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", cancel)
                .build()
        )
    }

    fun done(id: Int, name: String, folder: String, uri: Uri, mime: String) {
        val open = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE)
        val body = "$name\nمكان الحفظ: $folder"
        nm.notify(
            id,
            base(CH_DONE)
                .setContentTitle("تم التحميل ✓")
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun failed(id: Int, message: String) {
        nm.notify(
            id,
            base(CH_DONE)
                .setContentTitle("فشل التحميل")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancelled(id: Int) = nm.cancel(id)

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setColor(0xFFFFC53D.toInt())
        .setOnlyAlertOnce(true)
}
