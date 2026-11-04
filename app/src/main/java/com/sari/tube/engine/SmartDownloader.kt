package com.sari.tube.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Thrown when the user cancels. Partial parts are kept so the download can resume later. */
class DownloadCancelledException : IOException("Download cancelled")

/** The URL returned a web page or non-media response; not retried. */
class InvalidMediaException(message: String) : Exception(message)

/** Server ignored the Range header; caller must fall back to a single stream. */
private class RangeUnsupportedException : Exception("Server does not honor Range requests")

class DownloadControl {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() = cancelled.set(true)
}

sealed class DownloadEvent {
    data class Progress(val downloaded: Long, val total: Long, val bytesPerSec: Long) : DownloadEvent()
    data class Finished(val file: File, val bytes: Long) : DownloadEvent()
}

data class DownloadConfig(
    val maxConnections: Int = 4,
    val minSegmentBytes: Long = 4L * 1024 * 1024,
    val maxRetries: Int = 4,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val bufferBytes: Int = 64 * 1024,
    val progressIntervalMs: Long = 250
)

private data class Probe(val totalLength: Long, val acceptsRanges: Boolean, val etag: String?)

/**
 * Direct-file downloader: segmented (HTTP Range) when the server supports it,
 * otherwise a single streamed download. Writes incrementally to disk, resumes
 * from partial segments, and never publishes an incomplete file.
 *
 * Blocking call: run it on a background thread (IO dispatcher or WorkManager).
 */
class SmartDownloader(
    private val config: DownloadConfig = DownloadConfig(),
    private val extraHeaders: Map<String, String> = emptyMap()
) {

    fun download(
        url: String,
        output: File,
        control: DownloadControl = DownloadControl(),
        onEvent: (DownloadEvent) -> Unit = {}
    ): File {
        output.parentFile?.mkdirs()
        val probe = probe(url)
        val partsDir = File(output.parentFile, output.name + ".parts").apply { mkdirs() }
        val fingerprint = "$url|${probe.totalLength}|${probe.etag.orEmpty()}"
        val meta = File(partsDir, "meta.txt")
        // Partial data is only reusable if the remote file is unchanged.
        if (!meta.exists() || meta.readText() != fingerprint) resetParts(partsDir, fingerprint)

        val ticker = ProgressTicker(probe.totalLength, config.progressIntervalMs, onEvent)
        val tmp = try {
            if (probe.acceptsRanges && probe.totalLength >= 2 * config.minSegmentBytes) {
                segmented(url, probe, partsDir, control, ticker)
            } else {
                singleStream(url, partsDir, control, ticker)
            }
        } catch (e: RangeUnsupportedException) {
            resetParts(partsDir, fingerprint)
            singleStream(url, partsDir, control, ticker)
        }

        val expected = probe.totalLength
        if (expected > 0 && tmp.length() != expected) {
            throw IOException("Size mismatch: got ${tmp.length()} bytes, expected $expected")
        }
        if (output.exists()) output.delete()
        if (!tmp.renameTo(output)) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
        partsDir.deleteRecursively()
        onEvent(DownloadEvent.Finished(output, output.length()))
        return output
    }

    // ---------- Segmented path ----------

    private fun segmented(
        url: String, probe: Probe, partsDir: File, control: DownloadControl, ticker: ProgressTicker
    ): File {
        val total = probe.totalLength
        val count = minOf(config.maxConnections.toLong(), maxOf(1L, total / config.minSegmentBytes)).toInt()
        val segSize = total / count
        val ranges = (0 until count).map { i ->
            val start = i * segSize
            val end = if (i == count - 1) total - 1 else start + segSize - 1
            start to end
        }
        val failure = AtomicReference<Throwable?>(null)
        val stop = { control.isCancelled || failure.get() != null }
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures = ranges.mapIndexed { i, (start, end) ->
                pool.submit(Runnable {
                    try {
                        downloadSegment(url, partFile(partsDir, i), start, end, stop)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                })
            }
            while (futures.any { !it.isDone }) {
                Thread.sleep(config.progressIntervalMs)
                ticker.tick(partsDownloaded(partsDir, count))
                if (control.isCancelled) throw DownloadCancelledException()
            }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        failure.get()?.let { throw if (it is DownloadCancelledException) it else if (it is RangeUnsupportedException) it else IOException(it.message, it) }

        // Never merge a segment unless it is complete.
        ranges.forEachIndexed { i, (start, end) ->
            val expected = end - start + 1
            val actual = partFile(partsDir, i).length()
            if (actual != expected) throw IOException("Segment $i incomplete: $actual/$expected")
        }
        val merged = File(partsDir, "merged.tmp")
        FileOutputStream(merged).use { out ->
            for (i in 0 until count) partFile(partsDir, i).inputStream().use { it.copyTo(out, config.bufferBytes) }
        }
        return merged
    }

    private fun downloadSegment(url: String, part: File, start: Long, end: Long, stop: () -> Boolean) {
        val expected = end - start + 1
        var attempt = 0
        while (true) {
            if (stop()) throw DownloadCancelledException()
            try {
                if (part.exists() && part.length() > expected) part.delete()
                val have = if (part.exists()) part.length() else 0L
                if (have < expected) fetchRange(url, part, start + have, end, stop)
                if (part.length() != expected) throw IOException("Segment size mismatch")
                return
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: RangeUnsupportedException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt > config.maxRetries) throw IOException("Segment failed after retries: ${e.message}", e)
                Thread.sleep(backoff(attempt))
            }
        }
    }

    private fun fetchRange(url: String, part: File, from: Long, to: Long, stop: () -> Boolean) {
        val conn = open(url, "bytes=$from-$to")
        try {
            conn.connect()
            if (conn.responseCode != 206) throw RangeUnsupportedException()
            val cr = conn.getHeaderField("Content-Range")
            if (cr != null && !cr.startsWith("bytes $from-")) throw IOException("Unexpected Content-Range: $cr")
            conn.inputStream.use { input ->
                FileOutputStream(part, true).use { out -> pump(input, out, stop) }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Single-stream fallback ----------

    private fun singleStream(
        url: String, partsDir: File, control: DownloadControl, ticker: ProgressTicker
    ): File {
        val tmp = File(partsDir, "merged.tmp")
        var attempt = 0
        while (true) {
            try {
                tmp.delete()
                val conn = open(url, null)
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            pump(input, out, { control.isCancelled }) { ticker.tick(tmp.length()) }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                return tmp
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt > config.maxRetries) throw IOException("Download failed after retries: ${e.message}", e)
                Thread.sleep(backoff(attempt))
            }
        }
    }

    // ---------- Helpers ----------

    private fun probe(url: String): Probe {
        var attempt = 0
        while (true) {
            try {
                val conn = open(url, "bytes=0-0")
                try {
                    conn.connect()
                    checkMedia(conn)
                    return when (conn.responseCode) {
                        206 -> Probe(parseTotal(conn.getHeaderField("Content-Range")), true, conn.getHeaderField("ETag"))
                        200 -> Probe(conn.contentLengthLong, false, conn.getHeaderField("ETag"))
                        else -> throw IOException("HTTP ${conn.responseCode}")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                attempt++
                if (attempt > config.maxRetries) throw IOException("Cannot reach file: ${e.message}", e)
                Thread.sleep(backoff(attempt))
            }
        }
    }

    private fun pump(input: InputStream, out: OutputStream, stop: () -> Boolean, onChunk: () -> Unit = {}) {
        val buf = ByteArray(config.bufferBytes)
        while (true) {
            if (stop()) throw DownloadCancelledException()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            RateLimiter.consume(n)
            onChunk()
        }
    }

    private fun open(url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SariTube/0.1 (Android)")
            // Identity encoding keeps byte offsets aligned with Range requests.
            setRequestProperty("Accept-Encoding", "identity")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (range != null) setRequestProperty("Range", range)
        }

    private fun parseTotal(contentRange: String?): Long =
        contentRange?.substringAfterLast('/')?.toLongOrNull() ?: -1L

    private fun checkMedia(conn: HttpURLConnection) {
        val ct = conn.contentType?.lowercase().orEmpty()
        if (ct.startsWith("text/html") || ct.startsWith("application/json")) {
            throw InvalidMediaException("الرابط يعرض صفحة وليس ملف فيديو")
        }
    }

    private fun partFile(dir: File, index: Int) = File(dir, "part$index")

    private fun partsDownloaded(dir: File, count: Int): Long =
        (0 until count).sumOf { partFile(dir, it).length() }

    private fun resetParts(dir: File, fingerprint: String) {
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "meta.txt").writeText(fingerprint)
    }

    private fun backoff(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
}

/** Throttles progress callbacks and computes speed. */
private class ProgressTicker(
    private val total: Long,
    private val intervalMs: Long,
    private val onEvent: (DownloadEvent) -> Unit
) {
    private var lastTime = System.currentTimeMillis()
    private var lastBytes = 0L

    fun tick(downloaded: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTime < intervalMs) return
        val speed = if (now > lastTime) (downloaded - lastBytes) * 1000 / (now - lastTime) else 0L
        lastTime = now
        lastBytes = downloaded
        onEvent(DownloadEvent.Progress(downloaded, total, speed))
    }
}
