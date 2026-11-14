package com.sari.tube.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.sari.tube.engine.AppPrefs
import com.sari.tube.engine.UrlParser
import com.sari.tube.service.DownloadService

/** Receives a shared link and starts the download without opening the main screen. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent?.getStringExtra(Intent.EXTRA_TEXT) else null
        val url = text?.let { UrlParser.firstHttpUrl(it) }
        if (url != null) {
            if (!AppPrefs.allowMobileData(this) &&
                !AppPrefs.isOnWifiOrEthernet(this)
            ) {
                Toast.makeText(
                    this,
                    "التنزيل مقيّد على Wi-Fi حسب الإعدادات",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Failures are shown in the app's download list and in notifications. Without the
                // notification permission the notifications stay hidden, so the user is told here.
                val notificationsOff = Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                try {
                    DownloadService.start(this, url)
                    Toast.makeText(
                        this,
                        if (notificationsOff) {
                            "بدأ التحميل. فعّل الإشعارات لرؤية التقدم، أو افتح التطبيق لمتابعته"
                        } else {
                            "بدأ التحميل. تابع الإشعارات أو افتح التطبيق"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر بدء التحميل: ${e.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "لا يوجد رابط صالح", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
