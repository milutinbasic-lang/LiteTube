package app.litetube.player

import app.litetube.net.Http
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * googlevideo.com expects media requests that look like the YouTube client the URL was issued
 * for (same approach as NewPipe's YoutubeHttpDataSource). Shared by the player and downloads.
 */
object YoutubeRequests {

    /** YouTube's own clients fetch media with this tiny POST body; plain GETs get throttled or rejected. */
    val POST_BODY = byteArrayOf(0x78, 0)

    fun isVideoPlayback(host: String?, path: String?) =
        host.orEmpty().endsWith("googlevideo.com") && path?.startsWith("/videoplayback") == true

    fun headersFor(url: String): Map<String, String> {
        val headers = HashMap<String, String>()
        when {
            YoutubeParsingHelper.isWebStreamingUrl(url) ||
                YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url) -> {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com"
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
                headers["User-Agent"] = Http.USER_AGENT
            }
            YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                headers["User-Agent"] = YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> headers["User-Agent"] = Http.USER_AGENT
        }
        headers["TE"] = "trailers"
        return headers
    }
}
