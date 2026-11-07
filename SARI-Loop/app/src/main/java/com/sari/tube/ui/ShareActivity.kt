package com.sari.tube.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.sari.tube.engine.UrlParser
import com.sari.tube.service.DownloadService

/** Receives a shared link and starts the download without opening the main screen. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent?.getStringExtra(Intent.EXTRA_TEXT) else null
        val url = text?.let { UrlParser.firstHttpUrl(it) }
        if (url != null) {
            DownloadService.start(this, url)
            Toast.makeText(this, "بدأ التحميل — تابع الإشعارات", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لا يوجد رابط صالح", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
