package app.litetube.download

import app.litetube.net.Http
import app.litetube.player.YoutubeRequests
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Downloads a googlevideo URL in 2 MB ranges, the way YouTube's own apps fetch media.
 * Large single requests get throttled or cut off. Each chunk is retried, so a flaky
 * mobile connection doesn't restart the whole download.
 */
object ChunkDownloader {
    private const val CHUNK = 2L * 1024 * 1024

    fun download(url: String, out: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        var total = -1L
        var position = 0L
        var requestNumber = 0
        val headers = YoutubeRequests.headersFor(url)
        out.outputStream().buffered(64 * 1024).use { output ->
            while (total < 0 || position < total) {
                if (cancelled()) throw IOException("Cancelled")
                var attempt = 0
                while (true) {
                    try {
                        val end = if (total > 0) minOf(position + CHUNK, total) - 1 else position + CHUNK - 1
                        val builder = Request.Builder()
                            .url(url + "&rn=" + requestNumber++)
                            .post(YoutubeRequests.POST_BODY.toRequestBody())
                            .header("Range", "bytes=$position-$end")
                            .header("Accept-Encoding", "identity")
                        headers.forEach { (k, v) -> builder.header(k, v) }
                        Http.client.newCall(builder.build()).execute().use { response ->
                            if (response.code == 416 && total < 0) {
                                total = position
                                return@use
                            }
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty response")
                            if (response.code == 206) {
                                total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                    ?: total
                            }
                            val buffer = ByteArray(32 * 1024)
                            body.byteStream().use { input ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    position += n
                                    onProgress(position, total)
                                }
                            }
                            // A plain 200 means the server sent the whole file at once.
                            if (response.code == 200) total = position
                        }
                        break
                    } catch (e: IOException) {
                        if (cancelled() || ++attempt >= 4) throw e
                        Thread.sleep(1500L * attempt)
                    }
                }
            }
        }
    }
}
