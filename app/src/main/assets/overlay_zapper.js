// Overlay zapper: cosmetic removal of ad overlays and click hijackers.
//
// Registered through WebViewCompat.addDocumentStartJavaScript() for "*" (all
// frames) when the ad-block setting is on, so it runs before any page script in
// the top document and in cross-origin player iframes alike.
//
// It targets three patterns seen in the wild that network filtering cannot
// catch (they are created by first-party scripts):
//   1. transparent, empty, max-z-index absolute/fixed layers used as click traps;
//   2. hidden off-screen <a> popunder anchors;
//   3. fixed/absolute max-z-index ad boxes living inside open shadow roots
//      (invisible to normal DOM scans) — the host is removed instead.
(function () {
  "use strict";
  try {
    if (window.__tvbroOverlayZapper) return;
    window.__tvbroOverlayZapper = true;

    var HIGH_Z = 1000000;
    var AD_HREF = /\/(ad|ads|adv|advert)\/|[\?&]ad=|visit\.php|popunder|clickunder|onclickads|adserv|adserver|doubleclick|googlesyndication|adnxs|adsterra|propeller/i;
    var AD_TEXT = /close\s*ad|skip\s*ad|learn\s*more|sponsored|advertisement/i;

    function transparent(cs) {
      var bg = cs.backgroundColor;
      if (!bg || bg === "transparent") return true;
      return /^rgba\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)$/.test(bg);
    }

    // 1. transparent empty full-screen-ish layer pinned above everything
    function isClickTrap(el) {
      var tag = el.tagName;
      if (tag !== "DIV" && tag !== "INS" && tag !== "IFRAME" && tag !== "SECTION" && tag !== "A") return false;
      var cs = window.getComputedStyle(el);
      if (!cs || cs.display === "none" || cs.visibility === "hidden") return false;
      if (cs.position !== "absolute" && cs.position !== "fixed") return false;
      var z = parseInt(cs.zIndex, 10);
      if (!(z >= HIGH_Z)) return false;
      if (!transparent(cs)) return false;
      if (el.childElementCount > 0 || (el.textContent || "").trim()) return false;
      var r = el.getBoundingClientRect();
      return r.width >= 40 && r.height >= 40;
    }

    // 2. hidden anchor parked off-screen (classic popunder)
    function isPopunder(el) {
      if (el.tagName !== "A") return false;
      var cs = window.getComputedStyle(el);
      if (!cs || (cs.display !== "none" && cs.visibility !== "hidden")) return false;
      var href = el.getAttribute("href") || "";
      if (href && AD_HREF.test(href)) return true;
      if (href && href !== "#") return false;
      if (el.childElementCount > 0 || (el.textContent || "").trim()) return false;
      var r = el.getBoundingClientRect();
      return r.left <= -200 || r.top <= -200 || r.right <= -200 || r.bottom <= -200;
    }

    // 3. max-z-index ad box (usually autoplaying video) inside a shadow root
    function findShadowAdBox(root) {
      var els = root.querySelectorAll("*");
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var cs = window.getComputedStyle(el);
        if (!cs || (cs.position !== "fixed" && cs.position !== "absolute")) continue;
        var z = parseInt(cs.zIndex, 10);
        if (!(z >= HIGH_Z)) continue;
        var txt = (el.textContent || "").replace(/\s+/g, " ").trim();
        if (AD_TEXT.test(txt)) return el;
        if (el.querySelector && el.querySelector("video, iframe")) return el;
      }
      return null;
    }

    function remove(el) {
      try { el.remove(); } catch (e) {}
    }

    var running = false;
    function sweep() {
      if (running || !document.documentElement) return;
      running = true;
      try {
        var candidates = document.querySelectorAll("div,ins,iframe,section,a");
        for (var i = 0; i < candidates.length; i++) {
          var el = candidates[i];
          try {
            if (isClickTrap(el) || isPopunder(el)) { remove(el); continue; }
          } catch (e) {}
        }
        var all = document.getElementsByTagName("*");
        for (var j = 0; j < all.length; j++) {
          var host = all[j];
          var sr = host.shadowRoot;
          if (sr && findShadowAdBox(sr)) remove(host);
        }
      } catch (e) {
      } finally {
        running = false;
      }
    }

    var scheduled = false;
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      setTimeout(function () { scheduled = false; sweep(); }, 120);
    }

    try {
      new MutationObserver(schedule).observe(document, { childList: true, subtree: true });
    } catch (e) {}

    // Safety net for late/attribute-driven injections.
    setInterval(sweep, 2000);

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", sweep, { once: true });
    }
    schedule();
  } catch (e) {}
})();
