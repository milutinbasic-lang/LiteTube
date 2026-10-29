package io.github.milutinbasic_lang.instachat

/**
 * Injected into every Instagram page. Instagram is a single-page app that rebuilds its UI
 * constantly, so a MutationObserver re-applies this whenever the page changes. It:
 *
 * - hides the navigation bar (Home, Search/Explore, Reels, Create, Profile...);
 * - lets you watch a video someone sent you, but not swipe on to the next one: swiping is
 *   disabled in full-screen video viewers, and reels-style lists (scroll-snap lists containing
 *   videos) are held in place;
 * - on a single post, hides what's below it ("More posts from ...").
 *
 * Navigation that slips through anyway is still stopped by [UrlRules].
 */
object ChatOnlyScript {

    val JS = """
(function () {
  if (window.__chatOnly) return;
  window.__chatOnly = true;

  var NAV = ['/', '/explore/', '/reels/', '/explore', '/reels'];
  var MEDIA_PAGE = /^\/(?:[^\/]+\/)?(?:p|reels?|tv)\/[A-Za-z0-9_-]{6,}/;
  var css = document.createElement('style');
  css.textContent =
    '[data-chatonly-hidden]{display:none!important}' +
    '[data-chatonly-locked]{overscroll-behavior:none!important;touch-action:none!important}' +
    'a[href="/explore/"],a[href="/reels/"],a[href^="/stories/"]{display:none!important}' +
    'svg[aria-label="New post"],svg[aria-label="Create"],svg[aria-label="Notifications"]{display:none!important}';
  (document.head || document.documentElement).appendChild(css);

  function hide(el) { if (el && !el.hasAttribute('data-chatonly-hidden')) el.setAttribute('data-chatonly-hidden', ''); }

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
        if (navLinks(el).length >= 2) { hide(el); break; }
        el = el.parentElement;
      }
    }
  }

  // On a single post: keep the post, hide everything after it (more posts, suggested reels).
  function trimMediaPage() {
    if (!MEDIA_PAGE.test(location.pathname)) return;
    var main = document.querySelector('main');
    var article = main && main.querySelector('article');
    if (!article) return;
    for (var el = article; el && el !== main; el = el.parentElement) {
      for (var sib = el.nextElementSibling; sib; sib = sib.nextElementSibling) hide(sib);
    }
  }

  // Full-screen video viewers: a fixed element covering (nearly) the whole screen with a video.
  var viewer = null;
  function findViewer() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      for (var el = vids[i].parentElement; el && el !== document.body; el = el.parentElement) {
        if (getComputedStyle(el).position !== 'fixed') continue;
        var r = el.getBoundingClientRect();
        if (r.width >= innerWidth * 0.8 && r.height >= innerHeight * 0.8) return el;
      }
    }
    return null;
  }
  function updateViewer() {
    var v = findViewer();
    if (v === viewer) return;
    if (viewer) viewer.removeAttribute('data-chatonly-locked');
    viewer = v;
    if (viewer) viewer.setAttribute('data-chatonly-locked', '');
  }

  // Reels-style lists: scroll-snap containers holding videos. One swipe = next reel, so they're
  // held at the position they had when the finger touched down.
  function isReelList(el) {
    if (!el || el.nodeType !== 1 || !el.querySelector('video')) return false;
    var cs = getComputedStyle(el);
    return (cs.scrollSnapType && cs.scrollSnapType !== 'none') || el === viewer || (viewer && viewer.contains(el));
  }
  var held = [];
  document.addEventListener('touchstart', function (e) {
    held = [];
    for (var el = e.target; el && el.nodeType === 1; el = el.parentElement) {
      if (isReelList(el)) held.push({ el: el, top: el.scrollTop, left: el.scrollLeft });
    }
  }, { capture: true, passive: true });
  document.addEventListener('scroll', function (e) {
    for (var i = 0; i < held.length; i++) {
      var h = held[i];
      if (h.el === e.target && (h.el.scrollTop !== h.top || h.el.scrollLeft !== h.left)) {
        h.el.scrollTop = h.top;
        h.el.scrollLeft = h.left;
      }
    }
  }, true);
  // Swipe gestures in a viewer that moves videos with transforms instead of scrolling.
  document.addEventListener('touchmove', function (e) {
    if (viewer && viewer.contains(e.target)) e.preventDefault();
  }, { capture: true, passive: false });
  document.addEventListener('wheel', function (e) {
    if (viewer && viewer.contains(e.target)) e.preventDefault();
  }, { capture: true, passive: false });

  // Called by the app's Back button: closes an open viewer instead of leaving the chat.
  window.__chatOnlyBack = function () {
    if (!viewer || !document.contains(viewer)) return false;
    var close = viewer.querySelector('[aria-label="Close"],[aria-label="close"]');
    var btn = close && (close.closest('[role="button"],button') || close);
    if (btn) { btn.click(); return true; }
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
    return true;
  };

  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      hideNavBars();
      trimMediaPage();
      updateViewer();
    });
  }
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  schedule();
})();
""".trimIndent()
}
