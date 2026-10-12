package app.litetube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.litetube.net.Http
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds player sources for YouTube streams. googlevideo.com expects requests that look
 * like the client the URL was issued for (same approach as NewPipe's YoutubeHttpDataSource).
 */
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

    fun build(playback: Playback): MediaSource {
        playback.hlsUrl?.let {
            return HlsMediaSource.Factory(hlsFactory).createMediaSource(MediaItem.fromUri(it))
        }
        val progressive = ProgressiveMediaSource.Factory(progressiveFactory)
        val video = playback.videoUrl?.let { progressive.createMediaSource(MediaItem.fromUri(it)) }
        val audio = playback.audioUrl?.let { progressive.createMediaSource(MediaItem.fromUri(it)) }
        return when {
            video != null && audio != null -> MergingMediaSource(video, audio)
            video != null -> video
            audio != null -> audio
            else -> error("Nothing to play")
        }
    }

    private fun youtubeRequest(spec: DataSpec, addRequestNumber: Boolean): DataSpec {
        val uri = spec.uri
        val host = uri.host.orEmpty()
        if (!host.endsWith("googlevideo.com") || uri.path?.startsWith("/videoplayback") != true) {
            return spec
        }

        var url = uri.toString()
        if (addRequestNumber && !url.contains("&rn=")) {
            url += "&rn=" + requestNumber.getAndIncrement()
        }

        val headers = HashMap(spec.httpRequestHeaders)
        when {
            YoutubeParsingHelper.isWebStreamingUrl(url) ||
                YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url) -> {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com"
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }
            YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getVisionOsUserAgent(null)
        }
        headers["TE"] = "trailers"

        // YouTube's own clients fetch media with a tiny POST body; plain GETs get throttled or rejected.
        return spec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
    }

    private companion object {
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
