package io.github.milutinbasic_lang.instachat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private val json = """
        {
          "status": "ok",
          "viewer": {"pk": 111, "username": "me"},
          "inbox": {"threads": [
            {
              "thread_id": "340282366841710300949128100000000001",
              "thread_title": "ana",
              "is_group": false,
              "users": [{"pk": 222, "pk_id": "222", "username": "ana", "full_name": "Ana"}],
              "items": [{"item_id": "a1", "user_id": 222, "item_type": "text", "text": "Hi!"}],
              "last_seen_at": {"111": {"item_id": "a0", "timestamp": "1"}}
            },
            {
              "thread_id": "340282366841710300949128100000000002",
              "thread_title": "Family",
              "is_group": true,
              "users": [{"pk": 333, "username": "marko", "full_name": ""}],
              "items": [{"item_id": "b1", "user_id": 333, "item_type": "clip"}],
              "last_seen_at": {}
            },
            {
              "thread_id": "340282366841710300949128100000000003",
              "thread_title": "ivan",
              "users": [{"pk": 444, "username": "ivan"}],
              "items": [{"item_id": "c1", "user_id": 444, "item_type": "voice_media"}],
              "last_seen_at": {"111": {"item_id": "c1"}}
            },
            {
              "thread_id": "340282366841710300949128100000000004",
              "users": [{"pk": 555, "username": "zoe"}],
              "items": [{"item_id": "d1", "user_id": 111, "item_type": "text", "text": "sent by me"}]
            }
          ]}
        }
    """.trimIndent()

    @Test
    fun readsTheNewestMessageOfEachChat() {
        val chats = Inbox.parse(json, myUserId = null)!!
        assertEquals(4, chats.size)

        val ana = chats[0]
        assertEquals("Ana", ana.senderName)
        assertNull(ana.groupTitle)
        assertEquals("Hi!", ana.text)
        assertFalse(ana.handled)

        val family = chats[1]
        assertEquals("Family", family.groupTitle)
        assertEquals("marko", family.senderName)
        assertEquals("🎬 Sent a reel", family.text)
        assertFalse(family.handled)

        assertTrue("seen by me", chats[2].handled)
        assertEquals("🎤 Voice message", chats[2].text)
        assertTrue("sent by me", chats[3].handled)
    }

    @Test
    fun cookieUserIdIsUsedWhenGiven() {
        val chats = Inbox.parse(json, myUserId = "111")!!
        assertTrue(chats[3].handled)
    }

    @Test
    fun errorsGiveNull() {
        assertNull(Inbox.parse("""{"status": "fail", "message": "login_required"}""", null))
    }
}
