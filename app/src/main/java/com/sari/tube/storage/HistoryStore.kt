package com.sari.tube.storage

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DownloadRecord(
    val id: String,
    val name: String,
    val mime: String,
    val uri: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val thumbPath: String?,
    val finishedAt: Long
)

/** The list of files this app saved. Kept as JSON in private app storage. */
object HistoryStore {
    private const val FILE = "history.json"
    private val lock = Any()

    fun all(context: Context): List<DownloadRecord> = synchronized(lock) { read(context) }

    /** Reads duration and a video frame from the finished file, then records it. */
    fun add(context: Context, src: File, name: String, mime: String, uri: String): DownloadRecord {
        val id = UUID.randomUUID().toString()
        val (duration, thumb) = probe(context, src, id)
        val rec = DownloadRecord(id, name, mime, uri, src.length(), duration, thumb, System.currentTimeMillis())
        synchronized(lock) {
            val list = read(context).toMutableList()
            list.add(0, rec)
            write(context, list)
        }
        return rec
    }

    /** Removes the entry from the list only. The file in Downloads is not deleted. */
    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val list = read(context)
            list.firstOrNull { it.id == id }?.thumbPath?.let { File(it).delete() }
            write(context, list.filterNot { it.id == id })
        }
    }

    private fun probe(context: Context, src: File, id: String): Pair<Long, String?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(src.absolutePath)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            var thumb: String? = null
            if (hasVideo) {
                // Skip the first second when possible: the very first frame is often black.
                val at = if (duration > 2000L) 1_000_000L else 0L
                val frame = mmr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
                    val f = File(dir, "$id.jpg")
                    val small = scaleDown(frame, 240)
                    f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    thumb = f.absolutePath
                }
            }
            duration to thumb
        } catch (e: Exception) {
            0L to null
        } finally {
            mmr.release()
        }
    }

    private fun scaleDown(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val h = bmp.height * maxWidth / bmp.width
        return Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
    }

    private fun read(context: Context): List<DownloadRecord> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrElse { return emptyList() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DownloadRecord(
                id = o.getString("id"),
                name = o.getString("name"),
                mime = o.optString("mime"),
                uri = o.getString("uri"),
                sizeBytes = o.optLong("size"),
                durationMs = o.optLong("duration"),
                thumbPath = o.optString("thumb", "").takeIf { it.isNotBlank() },
                finishedAt = o.optLong("at")
            )
        }
    }

    private fun write(context: Context, list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("mime", r.mime)
                    .put("uri", r.uri)
                    .put("size", r.sizeBytes)
                    .put("duration", r.durationMs)
                    .put("thumb", r.thumbPath ?: "")
                    .put("at", r.finishedAt)
            )
        }
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
