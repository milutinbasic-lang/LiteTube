package app.litetube.download

import app.litetube.net.Http
import app.litetube.player.YoutubeRequests
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a googlevideo URL in 2 MB ranges. Each chunk is retried, so a flaky mobile
 * connection doesn't restart the whole download.
 *
 * Request styles are tried in order, and whichever YouTube accepts is kept for the rest of the file:
 *  1. Exactly what the player sends (known to work): POST with the issuing client's headers,
 *     a request number and an open-ended range, resuming where it stopped if cut off.
 *  2. NewPipe's downloader: a plain GET with a browser User-Agent and 2 MB ranges.
 *  3. The player's headers with 2 MB ranges.
 */
object ChunkDownloader {
    private const val CHUNK = 2L * 1024 * 1024

    private enum class Style { PLAYER_STREAM, PLAIN_GET, PLAYER_POST }

    class HttpError(val code: Int) : IOException("HTTP $code")

    fun download(url: String, out: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        var total = -1L
        var position = 0L
        var requestNumber = 0
        var styles = Style.values().toList()
        out.outputStream().buffered(64 * 1024).use { output ->
            while (total < 0 || position < total) {
                if (cancelled()) throw IOException("Cancelled")
                var attempt = 0
                while (true) {
                    val style = styles.first()
                    val end = when {
                        style == Style.PLAYER_STREAM -> -1L
                        total > 0 -> minOf(position + CHUNK, total) - 1
                        else -> position + CHUNK - 1
                    }
                    var connection: HttpURLConnection? = null
                    try {
                        connection = open(url, style, position, end, requestNumber++)
                        val code = connection.responseCode
                        if (code == 416 && total < 0) {
                            total = position
                            break
                        }
                        if (code !in 200..299) throw HttpError(code)
                        if (code == 206) {
                            total = connection.getHeaderField("Content-Range")
                                ?.substringAfterLast('/')?.toLongOrNull() ?: total
                        } else if (total < 0 && position == 0L) {
                            total = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        }
                        val buffer = ByteArray(32 * 1024)
                        val before = position
                        connection.inputStream.use { input ->
                            // Server ignored our resume range and started over: skip what we have.
                            if (code == 200 && position > 0) {
                                var toSkip = position
                                while (toSkip > 0) {
                                    val skipped = input.skip(toSkip)
                                    if (skipped <= 0) throw IOException("Couldn't resume download")
                                    toSkip -= skipped
                                }
                            }
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                position += n
                                onProgress(position, total)
                            }
                        }
                        // Size unknown and the stream ended: that's the whole file. If the size is
                        // known and we're short, the connection was cut: the loop resumes from here.
                        if (total < 0) total = position
                        if (position == before && position < total) throw IOException("No data received")
                        // This style works; stick with it.
                        styles = listOf(style)
                        break
                    } catch (e: IOException) {
                        if (cancelled()) throw e
                        // Refused before anything was downloaded: try the other request style.
                        if (e is HttpError && e.code == 403 && styles.size > 1) {
                            styles = styles.drop(1)
                            continue
                        }
                        if (++attempt >= 4) throw e
                        Thread.sleep(1500L * attempt)
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
        }
    }

    private fun open(url: String, style: Style, start: Long, end: Long, requestNumber: Int): HttpURLConnection {
        val target = if (style != Style.PLAIN_GET && !url.contains("&rn=")) "$url&rn=$requestNumber" else url
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        when {
            end >= 0 -> connection.setRequestProperty("Range", "bytes=$start-$end")
            start > 0 -> connection.setRequestProperty("Range", "bytes=$start-")
            // Like the player's first request: no Range header at all.
        }
        connection.setRequestProperty("Accept-Encoding", "identity")
        when (style) {
            Style.PLAIN_GET -> {
                connection.setRequestProperty("User-Agent", Http.USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")
            }
            Style.PLAYER_STREAM, Style.PLAYER_POST -> {
                YoutubeRequests.headersFor(url).forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(YoutubeRequests.POST_BODY.size)
                connection.outputStream.use { it.write(YoutubeRequests.POST_BODY) }
            }
        }
        return connection
    }
}
