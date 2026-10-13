package app.litetube.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Combines YouTube's separate video and audio files into one normal MP4 (or rewrites a single
 * audio file into a standard M4A) using Android's built-in muxer: no re-encoding, so it is fast,
 * lossless and needs no extra libraries.
 */
object Muxer {

    fun mux(inputs: List<File>, target: SaveTarget) {
        val muxer = if (target.file != null) {
            MediaMuxer(target.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            check(Build.VERSION.SDK_INT >= 26)
            MediaMuxer(target.descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        class Track(val extractor: MediaExtractor, val output: Int)

        val extractors = ArrayList<MediaExtractor>()
        try {
            val tracks = ArrayList<Track>()
            for (input in inputs) {
                val extractor = MediaExtractor().also { extractors += it }
                extractor.setDataSource(input.path)
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        // One extractor per track so samples can be interleaved by time.
                        val own = if (tracks.none { it.extractor === extractor }) extractor
                        else MediaExtractor().also { extractors += it; it.setDataSource(input.path) }
                        own.selectTrack(i)
                        tracks += Track(own, muxer.addTrack(own.getTrackFormat(i)))
                    }
                }
            }
            check(tracks.isNotEmpty()) { "No audio or video found" }

            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val active = tracks.toMutableList()
            while (active.isNotEmpty()) {
                // Write the earliest sample next so audio and video are interleaved.
                val next = active.minByOrNull { it.extractor.sampleTime }!!
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    active.remove(next)
                    continue
                }
                val keyFrame = next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, next.extractor.sampleTime, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(next.output, buffer, info)
                next.extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
