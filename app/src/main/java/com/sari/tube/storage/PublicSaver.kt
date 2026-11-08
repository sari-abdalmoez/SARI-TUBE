package com.sari.tube.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a finished file into the public Downloads folder (no storage permission needed on Android 10+). */
object PublicSaver {
    const val FOLDER = "Download/SARI Loop"

    fun save(context: Context, src: File, displayName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء الملف في التخزين")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذر فتح ملف الحفظ")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
