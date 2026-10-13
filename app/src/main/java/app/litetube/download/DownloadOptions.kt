package app.litetube.download

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** What can be downloaded: only formats Android can mux into MP4/M4A without re-encoding. */
object DownloadOptions {

    data class VideoOption(val label: String, val videoUrl: String, val audioUrl: String?)

    fun video(info: StreamInfo): List<VideoOption> {
        val audio = audio(info)
        val muxed = info.videoStreams.filter { usable(it) && it.format == MediaFormat.MPEG_4 }
            .map { VideoOption(label(it), it.content, null) to height(it) }
        val separate = if (audio != null) {
            info.videoOnlyStreams.filter { usable(it) && it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc") }
                .map { VideoOption(label(it), it.content, audio.content) to height(it) }
        } else {
            emptyList()
        }
        // One option per resolution; a ready-made single file wins when both exist.
        return (muxed + separate).distinctBy { it.second }.sortedByDescending { it.second }.map { it.first }
    }

    /** Best AAC (M4A) audio in the original language. */
    fun audio(info: StreamInfo): AudioStream? {
        val m4a = info.audioStreams.filter { usable(it) && it.format == MediaFormat.M4A }
        val original = m4a.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { m4a }
        return original.maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate * 1000 else it.bitrate }
    }

    private fun usable(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun height(s: VideoStream) =
        if (s.height > 0) s.height else s.getResolution().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun label(s: VideoStream) = "${height(s)}p" + if (s.fps > 30) " ${s.fps}fps" else ""
}
