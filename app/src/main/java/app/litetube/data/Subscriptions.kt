package app.litetube.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Subscription(val url: String, val name: String, val avatar: String?)

/** Subscriptions are stored only on the device. No account involved. */
object Subscriptions {
    private const val KEY = "list"
    private lateinit var sp: SharedPreferences
    private val items = LinkedHashMap<String, Subscription>()

    /** Bumped on every change so screens can tell when to refresh. */
    var version = 0
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sub = Subscription(o.getString("url"), o.optString("name"), o.optStringOrNull("avatar"))
                items[normalize(sub.url)] = sub
            }
        }
    }

    fun all(): List<Subscription> = items.values.sortedBy { it.name.lowercase() }

    fun isSubscribed(url: String?) = url != null && normalize(url) in items

    fun add(sub: Subscription) {
        items[normalize(sub.url)] = sub
        save()
    }

    fun remove(url: String) {
        items.remove(normalize(url))
        save()
    }

    /** Refreshes a channel's name/avatar without counting as a subscription change. */
    fun updateDetails(sub: Subscription) {
        val key = normalize(sub.url)
        if (items[key] == sub || key !in items) return
        items[key] = sub
        save(bumpVersion = false)
    }

    /** Adds the given subscriptions, returning how many were new. */
    fun addAll(subs: List<Subscription>): Int {
        var added = 0
        for (sub in subs) {
            val key = normalize(sub.url)
            if (key !in items) added++
            items[key] = sub
        }
        save()
        return added
    }

    private fun save(bumpVersion: Boolean = true) {
        if (bumpVersion) version++
        val arr = JSONArray()
        for (s in items.values) {
            arr.put(JSONObject().put("url", s.url).put("name", s.name).put("avatar", s.avatar))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    /** Export in NewPipe's format so the file can be imported into NewPipe too. */
    fun exportJson(): String {
        val arr = JSONArray()
        for (s in items.values) {
            arr.put(JSONObject().put("service_id", 0).put("url", s.url).put("name", s.name))
        }
        return JSONObject().put("app_version", "LiteTube").put("subscriptions", arr).toString(2)
    }

    /** Parses a NewPipe JSON export or a Google Takeout subscriptions.csv. */
    fun parseImport(text: String): List<Subscription> {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) {
            val arr = JSONObject(trimmed).optJSONArray("subscriptions") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optInt("service_id", 0) != 0) return@mapNotNull null
                val url = o.optString("url")
                if (url.isEmpty()) return@mapNotNull null
                Subscription(normalize(url), o.optString("name"), null)
            }
        }
        // Takeout CSV: "Channel Id,Channel Url,Channel Title"
        return trimmed.lineSequence().mapNotNull { line ->
            val cols = line.split(',', limit = 3)
            val id = cols.getOrNull(0)?.trim().orEmpty()
            if (!CHANNEL_ID.matches(id)) return@mapNotNull null
            Subscription(CHANNEL_PREFIX + id, cols.getOrNull(2)?.trim().orEmpty().ifEmpty { id }, null)
        }.toList()
    }

    private const val CHANNEL_PREFIX = "https://www.youtube.com/channel/"
    private val CHANNEL_ID = Regex("UC[\\w-]{22}")

    /** Maps the different URL forms of a channel to one key. */
    fun normalize(url: String): String {
        val id = Regex("/channel/(UC[\\w-]{22})").find(url)?.groupValues?.get(1)
        return if (id != null) CHANNEL_PREFIX + id else url.trimEnd('/')
    }
}
