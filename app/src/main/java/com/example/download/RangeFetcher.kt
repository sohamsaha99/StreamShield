package com.example.download

import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/** The stream URL stopped working (they expire after some hours): resolve the video again. */
class ExpiredUrlException : IOException("The stream link expired")

/**
 * Downloads a stream URL into a file in 512 KB ranges, as NewPipe's downloader does (YouTube
 * slows down or refuses large single requests). Resumes from the bytes already in [target].
 */
object RangeFetcher {
    private const val BLOCK_SIZE = 512L * 1024L
    private const val MAX_RETRIES = 4

    /** Blocking; returns the file size. [onProgress] gets (bytes done, total bytes or -1). */
    fun fetch(
        url: String,
        target: File,
        isStopped: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ): Long {
        var total = contentLengthFromUrl(url)
        var position = if (target.exists()) target.length() else 0L
        if (total in 1..position) {
            onProgress(position, total)
            return position
        }
        val buffer = ByteArray(64 * 1024)
        var failures = 0
        // NewPipe's downloader sends a desktop user agent; streams from YouTube's visionOS client
        // may want that client's own one (NewPipe's player sends it). Switch once if refused.
        var userAgent = NewPipeSupport.USER_AGENT
        var triedClientAgent = false
        RandomAccessFile(target, "rw").use { file ->
            file.seek(position)
            while (total < 0 || position < total) {
                if (isStopped()) throw CancellationException("Download stopped")
                val end = if (total > 0) min(position + BLOCK_SIZE, total) - 1 else position + BLOCK_SIZE - 1
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                    .header("Range", "bytes=$position-$end")
                    .build()
                try {
                    NewPipeSupport.http.newCall(request).execute().use { response ->
                        val code = response.code
                        if (code == 403 && !triedClientAgent && NewPipeSupport.isVisionOsUrl(url)) {
                            triedClientAgent = true
                            userAgent = NewPipeSupport.visionOsUserAgent()
                            throw IOException("HTTP 403, retrying with the client's user agent")
                        }
                        if (code == 403 || code == 410) throw ExpiredUrlException()
                        if (code == 416 && total < 0 && position > 0) {
                            // Asked past the end of a file of unknown length: it is complete.
                            total = position
                            return@use
                        }
                        if (code != 206 && !(code == 200 && position == 0L)) throw IOException("HTTP $code")
                        val body = response.body ?: throw IOException("Empty response")
                        if (total < 0) {
                            total = totalFromContentRange(response.header("Content-Range"))
                                ?: if (code == 200) body.contentLength() else -1L
                        }
                        val before = position
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                file.write(buffer, 0, read)
                                position += read
                                onProgress(position, total)
                                if (isStopped()) throw CancellationException("Download stopped")
                            }
                        }
                        if (position == before) throw IOException("Empty response")
                        // A 200 answer is the whole file.
                        if (code == 200) total = position
                    }
                    failures = 0
                } catch (e: ExpiredUrlException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    failures++
                    if (failures > MAX_RETRIES) throw e
                    Thread.sleep(1_000L * failures)
                }
                if (total < 0 && failures == 0) throw IOException("Unknown file size")
            }
        }
        return position
    }

    /** YouTube stream URLs carry the file size as the "clen" parameter. */
    fun contentLengthFromUrl(url: String): Long =
        try {
            Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }

    /** "bytes 0-524287/12345678" -> 12345678. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
