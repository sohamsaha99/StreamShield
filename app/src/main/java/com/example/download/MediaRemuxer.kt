package com.example.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Copies the samples of downloaded streams into one file with Android's MediaExtractor and
 * MediaMuxer (no re-encoding): video + audio into an MP4 or WebM, or a single YouTube audio stream
 * (a fragmented MP4) into a regular .m4a that every music player reads.
 */
object MediaRemuxer {

    fun remux(inputs: List<File>, output: File, container: Container) {
        val outputFormat = if (container == Container.WEBM) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
        if (output.exists()) output.delete()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val m = MediaMuxer(output.absolutePath, outputFormat)
            muxer = m
            val muxerTracks = IntArray(inputs.size)
            var maxSample = 1 shl 20
            var hasVideo = false
            inputs.forEachIndexed { i, file ->
                val extractor = MediaExtractor()
                extractors.add(extractor)
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("video/") || mime.startsWith("audio/")
                } ?: throw IOException("No audio or video in ${file.name}")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) hasVideo = true
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSample = max(maxSample, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                muxerTracks[i] = m.addTrack(format)
            }
            m.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(max(maxSample, if (hasVideo) 8 shl 20 else 1 shl 20))
            val info = MediaCodec.BufferInfo()
            val finished = BooleanArray(inputs.size)
            // Some audio starts slightly before zero (encoder delay); shift such a track so no
            // timestamp is negative.
            val offsets = LongArray(inputs.size) { i -> (-extractors[i].sampleTime).coerceAtLeast(0L) }
            while (true) {
                // Interleave by time: always write the track whose next sample comes first.
                var next = -1
                var nextTime = Long.MAX_VALUE
                for (i in extractors.indices) {
                    if (finished[i]) continue
                    if (extractors[i].sampleTrackIndex < 0) {
                        finished[i] = true
                        continue
                    }
                    val time = extractors[i].sampleTime + offsets[i]
                    if (time < nextTime) {
                        nextTime = time
                        next = i
                    }
                }
                if (next < 0) break
                val extractor = extractors[next]
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    finished[next] = true
                    continue
                }
                val keyFrame = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                val time = (extractor.sampleTime + offsets[next]).coerceAtLeast(0L)
                info.set(0, size, time, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(muxerTracks[next], buffer, info)
                extractor.advance()
            }
            m.stop()
            started = false
        } catch (e: Exception) {
            output.delete()
            throw if (e is IOException) e else IOException("Could not merge the streams: ${e.message}", e)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
