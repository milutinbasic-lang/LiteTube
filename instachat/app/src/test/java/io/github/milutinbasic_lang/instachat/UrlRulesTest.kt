package io.github.milutinbasic_lang.instachat

import io.github.milutinbasic_lang.instachat.UrlRules.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlRulesTest {

    private fun check(expected: Action, vararg urls: String) {
        for (url in urls) assertEquals(url, expected, UrlRules.decide(url))
    }

    @Test
    fun chatsAndLoginAreAllowed() = check(
        Action.ALLOW,
        "https://www.instagram.com/direct/inbox/",
        "https://www.instagram.com/direct/t/1234567890/",
        "https://www.instagram.com/direct/requests/",
        "https://www.instagram.com/direct/new/",
        "https://instagram.com/direct/inbox/",
        "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F",
        "https://www.instagram.com/accounts/onetap/?next=%2F",
        "https://www.instagram.com/accounts/password/reset/",
        "https://www.instagram.com/challenge/?next=/direct/inbox/",
        "https://www.instagram.com/two_factor?next=%2F",
        "https://www.facebook.com/login.php?skip_api_login=1",
        "https://m.facebook.com/dialog/oauth",
        "https://accountscenter.instagram.com/password_and_security/",
        "about:blank",
    )

    @Test
    fun homeFeedGoesToInbox() = check(
        Action.GO_TO_INBOX,
        "https://www.instagram.com/",
        "https://www.instagram.com",
        "https://www.instagram.com/?hl=en",
    )

    @Test
    fun nonChatPagesAreBlocked() = check(
        Action.BLOCK,
        "https://www.instagram.com/reels/",
        "https://www.instagram.com/reel/C1a2b3c4d5/",
        "https://www.instagram.com/p/C1a2b3c4d5/",
        "https://www.instagram.com/explore/",
        "https://www.instagram.com/explore/search/",
        "https://www.instagram.com/stories/someone/123/",
        "https://www.instagram.com/someone/",
        "https://www.instagram.com/accounts/activity/",
    )

    @Test
    fun otherSitesOpenOutside() = check(
        Action.EXTERNAL,
        "https://l.instagram.com/?u=https%3A%2F%2Fexample.com",
        "https://example.com/page",
        "mailto:someone@example.com",
        "intent://scan/#Intent;scheme=zxing;end",
    )
}
