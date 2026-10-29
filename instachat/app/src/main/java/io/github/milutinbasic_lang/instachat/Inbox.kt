package io.github.milutinbasic_lang.instachat

import org.json.JSONObject

/**
 * Reads the inbox JSON that Instagram's website loads from /api/v1/direct_v2/inbox/ and reduces
 * each chat to its newest message.
 */
object Inbox {

    data class Latest(
        val threadId: String,
        /** Chat name for group chats, else null. */
        val groupTitle: String?,
        val itemId: String,
        val senderName: String,
        val text: String,
        /** Sent by you, or already seen by you (in this app, the Instagram app or a computer). */
        val handled: Boolean,
    )

    fun parse(json: String, myUserId: String?): List<Latest>? {
        val root = JSONObject(json)
        if (root.optString("status") != "ok") return null
        val me = myUserId
            ?: root.optJSONObject("viewer")?.let { it.optString("pk_id").ifEmpty { it.optString("pk") } }
        val threads = root.optJSONObject("inbox")?.optJSONArray("threads") ?: return null
        val out = ArrayList<Latest>()
        for (i in 0 until threads.length()) {
            val thread = threads.getJSONObject(i)
            val item = thread.optJSONArray("items")?.optJSONObject(0) ?: continue
            val threadId = thread.optString("thread_id")
            val itemId = item.optString("item_id")
            if (threadId.isEmpty() || itemId.isEmpty()) continue
            val senderId = item.optString("user_id")

            val names = HashMap<String, String>()
            thread.optJSONArray("users")?.let { users ->
                for (u in 0 until users.length()) {
                    val user = users.getJSONObject(u)
                    val name = user.optString("full_name").ifEmpty { user.optString("username") }
                    names[user.optString("pk")] = name
                    names[user.optString("pk_id")] = name
                }
            }
            val seenByMe = me != null &&
                thread.optJSONObject("last_seen_at")?.optJSONObject(me)?.optString("item_id") == itemId

            out += Latest(
                threadId = threadId,
                groupTitle = if (thread.optBoolean("is_group")) thread.optString("thread_title").ifEmpty { null } else null,
                itemId = itemId,
                senderName = names[senderId] ?: thread.optString("thread_title").ifEmpty { "Instagram" },
                text = describe(item),
                handled = (me != null && senderId == me) || seenByMe,
            )
        }
        return out
    }

    private fun describe(item: JSONObject): String {
        val type = item.optString("item_type")
        return when {
            type == "text" -> item.optString("text").ifEmpty { "New message" }
            type == "link" -> item.optJSONObject("link")?.optString("text")?.ifEmpty { null } ?: "Sent a link"
            type == "media" || type == "raw_media" -> {
                val media = item.optJSONObject("media") ?: item.optJSONObject("raw_media")
                if (media?.optInt("media_type") == 2) "🎥 Video" else "📷 Photo"
            }
            type == "voice_media" -> "🎤 Voice message"
            type == "animated_media" -> "GIF"
            type == "like" -> "❤️"
            type == "clip" || type == "xma_clip" -> "🎬 Sent a reel"
            type == "reel_share" -> "Replied to your story"
            type == "story_share" || type == "xma_story_share" -> "Shared a story"
            type == "action_log" -> item.optJSONObject("action_log")?.optString("description")?.ifEmpty { null }
                ?: "New message"
            type.contains("share") || type.startsWith("xma") -> "Shared a post"
            else -> "New message"
        }
    }
}
