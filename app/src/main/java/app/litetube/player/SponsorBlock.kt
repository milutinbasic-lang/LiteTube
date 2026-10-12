package app.litetube.player

import app.litetube.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest

/**
 * Skips sponsor reads and other in-video ads using the community-run SponsorBlock database
 * (https://sponsor.ajay.app). YouTube's own ads never load in LiteTube: the app fetches the
 * video files directly. Sponsor segments are part of the video itself, so this covers the rest.
 *
 * Privacy: only the first 4 characters of the SHA-256 of the video ID are sent. The server
 * returns segments for every video sharing that prefix, and the match happens on the device.
 */
object SponsorBlock {

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    private const val API = "https://sponsor.ajay.app/api/skipSegments/"

    suspend fun segments(videoId: String, categories: Set<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = (API + sha256(videoId).take(4)).toHttpUrl().newBuilder()
                .addQueryParameter("categories", JSONArray(categories.toList()).toString())
                .addQueryParameter("actionTypes", "[\"skip\"]")
                .build()
            val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            Http.client.newCall(request).execute().use { response ->
                // 404 simply means no segments are known for any video with this prefix.
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) throw IOException("SponsorBlock HTTP ${response.code}")
                parse(response.body?.string().orEmpty(), videoId)
            }
        }
    }

    private fun parse(json: String, videoId: String): List<Segment> {
        val videos = JSONArray(json)
        for (i in 0 until videos.length()) {
            val video = videos.getJSONObject(i)
            if (video.optString("videoID") != videoId) continue
            val list = video.optJSONArray("segments") ?: return emptyList()
            return (0 until list.length()).mapNotNull { j ->
                val s = list.getJSONObject(j)
                val range = s.optJSONArray("segment") ?: return@mapNotNull null
                val start = (range.optDouble(0) * 1000).toLong()
                val end = (range.optDouble(1) * 1000).toLong()
                if (end - start < 1000) null else Segment(start, end, s.optString("category"))
            }.sortedBy { it.startMs }
        }
        return emptyList()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
