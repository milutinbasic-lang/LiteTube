package io.github.milutinbasic_lang.instachat

import java.net.URI

/**
 * Decides what happens when the WebView navigates somewhere. Only the Direct inbox, chats and the
 * pages needed to log in are allowed; feed, reels, explore, stories, posts and profiles are not.
 * Plain Kotlin (no Android classes) so it can be unit-tested on the JVM.
 */
object UrlRules {

    const val INBOX_URL = "https://www.instagram.com/direct/inbox/"

    enum class Action {
        /** Load it in the app. */
        ALLOW,
        /** The home feed: send the user to the inbox instead. */
        GO_TO_INBOX,
        /** An Instagram page that isn't chat (reels, posts, explore, profiles...). */
        BLOCK,
        /** Not Instagram: open in the browser or the app that handles it. */
        EXTERNAL,
    }

    private val instagramHosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")

    // Login with Facebook, Accounts Center (password, 2FA), and Meta's login helpers.
    private val loginHosts = setOf(
        "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com",
        "accountscenter.instagram.com", "accountscenter.facebook.com",
        "auth.instagram.com", "auth.meta.com", "www.meta.com", "meta.com",
    )

    private val allowedPathPrefixes = listOf(
        "/direct",          // inbox, chats, requests, new message
        "/accounts/",       // login, signup, one-tap "save login", password reset, settings
        "/challenge",       // "was this you?" / suspicious login checks
        "/auth_platform",   // login codes
        "/two_factor",
        "/api/", "/ajax/", "/graphql", "/oauth", "/sso",
        "/consent", "/session", "/web/",
        "/legal", "/privacy", "/terms", "/emails",
    )

    // Allowed above as part of /accounts/, but they're the notifications/likes feed.
    private val blockedPathPrefixes = listOf("/accounts/activity")

    fun decide(url: String): Action {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return Action.BLOCK
        }
        val scheme = uri.scheme?.lowercase() ?: return Action.BLOCK
        when (scheme) {
            "about", "blob", "data", "javascript" -> return Action.ALLOW
            "http", "https" -> Unit
            else -> return Action.EXTERNAL // mailto:, tel:, intent:, market: ...
        }
        val host = uri.host?.lowercase() ?: return Action.BLOCK
        if (host in loginHosts) return Action.ALLOW
        if (host !in instagramHosts) {
            // Includes l.instagram.com, Instagram's link redirector for links sent in chats.
            return Action.EXTERNAL
        }
        val path = uri.path.orEmpty().lowercase()
        if (path.isEmpty() || path == "/") return Action.GO_TO_INBOX
        if (blockedPathPrefixes.any { path.startsWith(it) }) return Action.BLOCK
        if (allowedPathPrefixes.any { path.startsWith(it) }) return Action.ALLOW
        return Action.BLOCK
    }
}
