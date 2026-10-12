package app.litetube.player

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** What to hand to the player: either a video (+ optional separate audio) URL, or an HLS manifest. */
data class Playback(
    val videoUrl: String?,
    val audioUrl: String?,
    val hlsUrl: String?,
    val label: String,
) {
    val isAudioOnly get() = videoUrl == null && hlsUrl == null
}

/**
 * Chooses streams with low-end phones in mind: by default H.264 (hardware-decoded on
 * practically every device) at ≤30fps, never above the user's quality cap.
 */
object StreamPicker {

    fun isLive(info: StreamInfo) =
        info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM

    fun pick(info: StreamInfo, maxHeight: Int, lowPower: Boolean, audioOnly: Boolean): Playback? {
        val hls = info.hlsUrl?.takeIf { it.isNotEmpty() }
        if (isLive(info)) return hls?.let { Playback(null, null, it, "Live") }

        val audio = bestAudio(info.audioStreams, lowData = maxHeight <= 240 || audioOnly && lowPower)
        if (audioOnly && audio != null) return Playback(null, audio.content, null, "Audio")

        val video = bestVideo(videoCandidates(info, audio != null, lowPower), maxHeight)
        if (video != null) {
            val label = "${height(video)}p"
            return if (video.isVideoOnly()) {
                Playback(video.content, audio?.content, null, label)
            } else {
                Playback(video.content, null, null, label)
            }
        }
        if (hls != null) return Playback(null, null, hls, "Auto")
        return audio?.let { Playback(null, it.content, null, "Audio") }
    }

    /** Heights the user can choose from in the quality menu. */
    fun availableHeights(info: StreamInfo, lowPower: Boolean): List<Int> {
        val hasAudio = info.audioStreams.any { isProgressive(it.deliveryMethod, it.isUrl) }
        return videoCandidates(info, hasAudio, lowPower).map { height(it) }.filter { it > 0 }
            .distinct().sorted()
    }

    private fun videoCandidates(info: StreamInfo, hasAudio: Boolean, lowPower: Boolean): List<VideoStream> {
        val muxed = info.videoStreams.filter { isProgressive(it.deliveryMethod, it.isUrl) }
        val videoOnly = if (hasAudio) {
            info.videoOnlyStreams.filter { isProgressive(it.deliveryMethod, it.isUrl) }
        } else {
            emptyList()
        }
        val all = muxed + videoOnly
        if (!lowPower) return all
        return all.filter { isAvc(it) && it.fps <= 30 }
            .ifEmpty { all.filter { isAvc(it) } }
            .ifEmpty { all }
    }

    private fun bestVideo(candidates: List<VideoStream>, maxHeight: Int): VideoStream? {
        if (candidates.isEmpty()) return null
        val within = candidates.filter { height(it) in 1..maxHeight }
        if (within.isEmpty()) return candidates.minByOrNull { height(it).takeIf { h -> h > 0 } ?: Int.MAX_VALUE }
        // Highest resolution within the cap; on ties prefer a single muxed file, then lower fps.
        return within.maxWithOrNull(
            compareBy<VideoStream>({ height(it) }, { !it.isVideoOnly() }, { -it.fps })
        )
    }

    private fun bestAudio(streams: List<AudioStream>, lowData: Boolean): AudioStream? {
        val usable = streams.filter { isProgressive(it.deliveryMethod, it.isUrl) }
        if (usable.isEmpty()) return null
        // Prefer the original language track over dubbed / descriptive ones.
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { usable }
        // AAC (m4a) decodes cheaply everywhere; Opus is fine too but needs more CPU on old devices.
        val preferred = original.filter { it.format == MediaFormat.M4A }.ifEmpty { original }
        return if (lowData) {
            preferred.minByOrNull { bitrate(it) }
        } else {
            preferred.filter { bitrate(it) <= 160_000 }.maxByOrNull { bitrate(it) }
                ?: preferred.minByOrNull { bitrate(it) }
        }
    }

    private fun bitrate(a: AudioStream): Int =
        if (a.averageBitrate > 0) a.averageBitrate * 1000 else a.bitrate

    private fun isProgressive(method: DeliveryMethod, isUrl: Boolean) =
        isUrl && method == DeliveryMethod.PROGRESSIVE_HTTP

    private fun isAvc(s: VideoStream): Boolean {
        val codec = s.codec
        return if (codec.isNullOrEmpty()) s.format == MediaFormat.MPEG_4 else codec.startsWith("avc")
    }

    fun height(s: VideoStream): Int =
        if (s.height > 0) s.height else s.getResolution().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}
