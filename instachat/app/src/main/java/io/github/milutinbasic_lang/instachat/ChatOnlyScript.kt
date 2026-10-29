package io.github.milutinbasic_lang.instachat

/**
 * Injected into every Instagram page. Hides the navigation bar (Home, Search/Explore, Reels,
 * Create, Profile...) so only chats are visible. Instagram is a single-page app that rebuilds
 * its UI constantly, so a MutationObserver re-applies this whenever the page changes.
 * Navigation that slips through anyway is still stopped by [UrlRules].
 */
object ChatOnlyScript {

    val JS = """
(function () {
  if (window.__chatOnly) return;
  window.__chatOnly = true;

  var NAV = ['/', '/explore/', '/reels/', '/explore', '/reels'];
  var css = document.createElement('style');
  css.textContent =
    '[data-chatonly-hidden]{display:none!important}' +
    'a[href="/explore/"],a[href="/reels/"],a[href^="/reels/"],a[href^="/stories/"]{display:none!important}' +
    'svg[aria-label="New post"],svg[aria-label="Create"],svg[aria-label="Notifications"]{display:none!important}';
  (document.head || document.documentElement).appendChild(css);

  function navLinks(root) {
    var out = [];
    var links = root.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      if (NAV.indexOf(links[i].getAttribute('href')) >= 0) out.push(links[i]);
    }
    return out;
  }

  // The nav bar is the smallest element that holds two or more of the Home/Explore/Reels links.
  function hideNavBars() {
    var links = navLinks(document);
    for (var i = 0; i < links.length; i++) {
      var el = links[i].parentElement;
      while (el && el !== document.body) {
        if (el.hasAttribute('data-chatonly-hidden')) break;
        if (navLinks(el).length >= 2) {
          el.setAttribute('data-chatonly-hidden', '');
          break;
        }
        el = el.parentElement;
      }
    }
  }

  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () { queued = false; hideNavBars(); });
  }
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  schedule();
})();
""".trimIndent()
}
