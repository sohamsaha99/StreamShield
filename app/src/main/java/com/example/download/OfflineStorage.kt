package com.example.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.thumbnailUrl
import java.io.File
import java.io.IOException

/**
 * Where downloads live.
 * - Manual downloads: the public Movies/StreamShield and Music/StreamShield folders (Android 10+,
 *   through MediaStore, no permission needed), so other apps and file managers see them. On
 *   Android 7-9 they go to the app's own folder on the shared storage instead.
 * - Smart downloads: inside the app only (they change daily).
 * A download's location is a content:// URI or an absolute file path.
 */
object OfflineStorage {
    private const val FOLDER = "StreamShield"

    fun tempDir(context: Context): File = File(context.cacheDir, "downloads").apply { mkdirs() }

    private fun smartDir(context: Context): File = File(context.filesDir, "smart").apply { mkdirs() }

    fun thumbnailFile(context: Context, videoId: String): File =
        File(File(context.filesDir, "thumbs").apply { mkdirs() }, "$videoId.jpg")

    fun mimeType(container: Container, audioOnly: Boolean): String = when {
        audioOnly && container == Container.MP4 -> "audio/mp4"
        audioOnly -> "audio/webm"
        container == Container.MP4 -> "video/mp4"
        else -> "video/webm"
    }

    fun extension(container: Container, audioOnly: Boolean): String = when {
        audioOnly && container == Container.MP4 -> "m4a"
        else -> if (container == Container.MP4) "mp4" else "webm"
    }

    /** Copies a finished file into the Music or Movies folder; returns its location. */
    fun savePublic(
        context: Context,
        source: File,
        title: String,
        container: Container,
        audioOnly: Boolean
    ): String {
        val name = safeFileName(title) + "." + extension(container, audioOnly)
        val mime = mimeType(container, audioOnly)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (audioOnly) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val folder = (if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + FOLDER
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file in $folder")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Could not write the file")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw if (e is IOException) e else IOException(e.message, e)
            }
            return uri.toString()
        }
        val base = context.getExternalFilesDir(if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val dir = File(base, FOLDER).apply { mkdirs() }
        val target = uniqueFile(dir, name)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    /** Smart downloads: a file inside the app. */
    fun saveSmart(context: Context, source: File, videoId: String, container: Container): String {
        val target = File(smartDir(context), videoId + "." + extension(container, audioOnly = true))
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun delete(context: Context, location: String): Boolean {
        if (location.isBlank()) return true
        return try {
            if (location.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(location), null, null) > 0
            } else {
                File(location).delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun exists(context: Context, location: String): Boolean {
        if (location.isBlank()) return false
        return if (location.startsWith("content://")) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        } else {
            File(location).exists()
        }
    }

    fun playableUri(location: String): Uri =
        if (location.startsWith("content://")) Uri.parse(location) else Uri.fromFile(File(location))

    /** Small cover image for the Downloads list and the offline player (works offline). */
    fun saveThumbnail(context: Context, videoId: String) {
        val file = thumbnailFile(context, videoId)
        if (file.exists() && file.length() > 0) return
        try {
            val request = okhttp3.Request.Builder().url(thumbnailUrl(videoId)).build()
            NewPipeSupport.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                file.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    fun deleteThumbnail(context: Context, videoId: String) {
        thumbnailFile(context, videoId).delete()
    }

    fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "video" }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (file.exists()) {
            file = File(dir, "$stem ($n).$ext")
            n++
        }
        return file
    }
}
