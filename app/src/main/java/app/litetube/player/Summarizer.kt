package app.litetube.player

import app.litetube.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * On-device video summary: no AI service, nothing uploaded. Uses the video's chapters if it has
 * them, and picks the most informative moments from its captions (a TF-IDF style ranking of
 * ~30 second windows, which works for any language without word lists).
 */
object Summarizer {

    data class Moment(val positionMs: Long, val text: String)
    data class Summary(val chapters: List<Moment>, val keyMoments: List<Moment>, val captionLanguage: String?)

    private data class Cue(val startMs: Long, val text: String)

    suspend fun summarize(info: StreamInfo, preferredLanguage: String?): Summary = withContext(Dispatchers.Default) {
        val chapters = info.streamSegments.orEmpty()
            .map { Moment(it.startTimeSeconds * 1000L, it.title) }

        val options = Captions.options(info)
        val original = Captions.baseLanguage(info.languageInfo?.language)
        // Prefer the caption language the user reads, then the video's own language, then English.
        val option = options.firstOrNull { it.language == preferredLanguage }
            ?: options.firstOrNull { it.language == original }
            ?: options.firstOrNull { it.language == Captions.baseLanguage(Locale.getDefault().language) }
            ?: options.firstOrNull { it.language == "en" }
            ?: options.firstOrNull()
        val moments = option?.let { pickMoments(parseVtt(fetch(it.stream.content))) }.orEmpty()
        Summary(chapters, moments, option?.label)
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        Http.client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
    }

    private val TIMING = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s+-->""")
    private val TAGS = Regex("<[^>]*>")

    private fun parseVtt(vtt: String): List<Cue> {
        val cues = ArrayList<Cue>()
        var previousLine = ""
        val lines = vtt.lines()
        var i = 0
        while (i < lines.size) {
            val match = TIMING.find(lines[i].trim())
            i++
            if (match == null) continue
            val (h, m, s, ms) = match.destructured
            val start = ((h.ifEmpty { "0" }.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                val line = TAGS.replace(lines[i], "").replace("&amp;", "&").replace("&gt;", ">")
                    .replace("&lt;", "<").replace("&#39;", "'").replace("&quot;", "\"").trim()
                // Auto-generated captions repeat the previous line in each cue ("rolling" captions).
                if (line.isNotEmpty() && line != previousLine) {
                    text.append(line).append(' ')
                    previousLine = line
                }
                i++
            }
            if (text.isNotBlank()) cues += Cue(start, text.toString().trim())
        }
        return cues
    }

    private fun pickMoments(cues: List<Cue>): List<Moment> {
        if (cues.isEmpty()) return emptyList()
        // Group captions into ~30 second windows.
        val windows = ArrayList<Cue>()
        var start = cues.first().startMs
        val text = StringBuilder()
        for (cue in cues) {
            if (cue.startMs - start >= 30_000 && text.isNotEmpty()) {
                windows += Cue(start, text.toString().trim())
                text.clear()
                start = cue.startMs
            }
            text.append(cue.text).append(' ')
        }
        if (text.isNotBlank()) windows += Cue(start, text.toString().trim())

        val tokenized = windows.map { w ->
            w.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        }
        val documentFrequency = HashMap<String, Int>()
        tokenized.forEach { words -> words.toSet().forEach { documentFrequency[it] = (documentFrequency[it] ?: 0) + 1 } }
        val n = windows.size.toDouble()

        val scores = tokenized.map { words ->
            if (words.isEmpty()) return@map 0.0
            val counts = words.groupingBy { it }.eachCount()
            val score = counts.entries.sumOf { (word, count) ->
                val df = documentFrequency[word] ?: 1
                // Words in most windows act like stop words in any language; ignore them.
                if (n >= 4 && df > n * 0.5) 0.0 else count * ln(n / df + 1)
            }
            score / sqrt(words.size.toDouble())
        }
        val count = (windows.size / 6).coerceIn(3, 8).coerceAtMost(windows.size)
        return windows.indices.sortedByDescending { scores[it] }.take(count).sorted()
            .map { Moment(windows[it].startMs, shorten(windows[it].text)) }
    }

    private fun shorten(text: String, max: Int = 220): String =
        if (text.length <= max) text else text.take(max).substringBeforeLast(' ') + "…"
}
