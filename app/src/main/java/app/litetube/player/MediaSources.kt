package app.litetube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import app.litetube.net.Http
import java.util.concurrent.atomic.AtomicLong

/** A side-loaded caption track (only the one the user picked is ever downloaded). */
data class CaptionTrack(val url: String, val mimeType: String, val language: String)

/** Builds player sources for YouTube streams, with optional captions. */
@OptIn(UnstableApi::class)
class MediaSources {

    private val http = DefaultHttpDataSource.Factory()
        // Set through default properties (not setUserAgent) so per-request headers can override it.
        .setDefaultRequestProperties(mapOf("User-Agent" to Http.USER_AGENT))
        .setAllowCrossProtocolRedirects(true)
        .setKeepPostFor302Redirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    private val requestNumber = AtomicLong()

    private val progressiveFactory =
        ResolvingDataSource.Factory(http) { spec -> youtubeRequest(spec, addRequestNumber = true) }
    private val hlsFactory =
        ResolvingDataSource.Factory(http) { spec -> youtubeRequest(spec, addRequestNumber = false) }
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

    /** [metadata] is shown in the notification and on the lock screen. */
    fun build(playback: Playback, metadata: MediaMetadata, caption: CaptionTrack?): MediaSource {
        fun item(url: String) = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
        val main = playback.hlsUrl?.let {
            HlsMediaSource.Factory(hlsFactory).createMediaSource(item(it))
        } ?: run {
            val progressive = ProgressiveMediaSource.Factory(progressiveFactory)
            val video = playback.videoUrl?.let { progressive.createMediaSource(item(it)) }
            val audio = playback.audioUrl?.let { progressive.createMediaSource(item(it)) }
            when {
                video != null && audio != null -> MergingMediaSource(video, audio)
                video != null -> video
                audio != null -> audio
                else -> error("Nothing to play")
            }
        }
        return if (caption != null) MergingMediaSource(main, captionSource(caption)) else main
    }

    // Same construction Media3's DefaultMediaSourceFactory uses for side-loaded subtitles.
    private fun captionSource(caption: CaptionTrack): MediaSource {
        val format = Format.Builder()
            .setSampleMimeType(caption.mimeType)
            .setLanguage(caption.language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val extractors = ExtractorsFactory {
            arrayOf<Extractor>(SubtitleExtractor(subtitleParserFactory.create(format), format))
        }
        return ProgressiveMediaSource.Factory(http, extractors)
            .createMediaSource(MediaItem.fromUri(caption.url))
    }

    private fun youtubeRequest(spec: DataSpec, addRequestNumber: Boolean): DataSpec {
        val uri = spec.uri
        if (!YoutubeRequests.isVideoPlayback(uri.host, uri.path)) return spec

        var url = uri.toString()
        if (addRequestNumber && !url.contains("&rn=")) {
            url += "&rn=" + requestNumber.getAndIncrement()
        }
        val headers = HashMap(spec.httpRequestHeaders)
        headers.putAll(YoutubeRequests.headersFor(url))
        return spec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(YoutubeRequests.POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
    }
}
