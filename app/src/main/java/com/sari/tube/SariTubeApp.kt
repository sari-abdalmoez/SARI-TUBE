package com.sari.tube

import android.app.Application
import com.sari.tube.engine.MetadataKeys
import com.sari.tube.engine.YtDlpEngine

class SariTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MetadataKeys.init(this)
        // Metadata keys are read from private preferences, see engine/Metadata.kt
        // التهيئة تعمل في خيط خلفي. استدعاء init على الخيط الرئيسي قد يسبب ANR بسبب استخراج Python.
        YtDlpEngine.initializeAsync(this)
    }
}
