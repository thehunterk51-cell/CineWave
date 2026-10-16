/* CineWave in-page ad guard.
 *
 * Runs inside every page the WebView loads, alongside the native host blocking
 * in MainActivity. It handles what the network layer cannot do on its own:
 *
 *   1. install the cosmetic stylesheet as early as the document allows
 *   2. neutralise window.open() popunders
 *   3. drop the new-window target so clicks stay under the app's control
 *   4. remove frames / images whose host the native layer blocked
 *      (reported through setBlockedHosts())
 *   5. remove overlay, sticky, floating and full-page ad layers
 *   6. keep doing all of the above while the page mutates
 *
 * Everything is wrapped in try/catch: a broken page must not be made worse by
 * the guard itself.
 */
(function () {
  "use strict";

  var CW = window.__cinewave || (window.__cinewave = {});
  var cfg = window.__cinewaveCfg || { ads: true, popups: true };

  if (CW.__installed) {
    CW.setBlockedHosts(CW.__pendingHosts || []);
    CW.sweep();
    return;
  }
  CW.__installed = true;
  CW.hosts = [];
  CW.enabled = cfg.ads !== false;      /* network rules + cosmetic filtering */
  CW.popups = cfg.popups !== false;    /* window.open / redirect protection  */
  CW.fullscreen = false;               /* the app puts the player fullscreen  */

  /* While the video is fullscreen the guard gets out of the way completely.
   * Players build their own fullscreen layer - position fixed, inset 0, a very
   * high z-index and usually no text - which looks exactly like the ad overlay
   * this file removes, so sweeping at that moment would delete the picture. */
  CW.setFullscreen = function (on) {
    CW.fullscreen = !!on;
    if (!on) CW.sweep();
  };

  function pageIsFullscreen() {
    try {
      return !!(document.fullscreenElement || document.webkitFullscreenElement ||
                document.mozFullScreenElement || document.msFullscreenElement);
    } catch (e) {
      return false;
    }
  }

  /* ---------------------------------------------------------------- hosts */

  function hostOf(url) {
    if (!url) return "";
    try {
      var h = new URL(url, location.href).hostname;
      return h ? h.toLowerCase().replace(/\.$/, "") : "";
    } catch (e) {
      return "";
    }
  }

  function hostBlocked(host) {
    if (!host) return false;
    var list = CW.hosts;
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      if (host === b || host.slice(-(b.length + 1)) === "." + b) return true;
    }
    return false;
  }

  CW.setBlockedHosts = function (hosts) {
    if (!hosts || !hosts.length) return;
    CW.__pendingHosts = hosts;
    var list = CW.hosts;
    for (var i = 0; i < hosts.length && list.length < 200; i++) {
      if (list.indexOf(hosts[i]) === -1) list.push(hosts[i]);
    }
  };

  /* ------------------------------------------------------------------ css */

  var cssText = window.__cinewaveCssText || CW.cssText || "";

  function installCss() {
    if (!CW.enabled || !cssText) return false;
    try {
      var head = document.head || document.documentElement;
      if (!head) return false;
      if (!document.getElementById("cinewave-css")) {
        var style = document.createElement("style");
        style.id = "cinewave-css";
        style.type = "text/css";
        style.appendChild(document.createTextNode(cssText));
        head.insertBefore(style, head.firstChild);
      }
      return true;
    } catch (e) {
      return false;
    }
  }
  CW.installCss = installCss;
  installCss();

  /* --------------------------------------------------------- ad detection */

  /* Word-boundary signature match. A bare "ad"/"ads" would also fire on
   * "download", "header" and "load", so only clearly ad-shaped tokens count,
   * and "overlay"/"banner" alone are excluded for the same reason. */
  var AD_WORD = /(^|[^a-z0-9])(ad|ads|adv|advert[a-z]*|popup|popunder[a-z]*|sponsor[a-z]*|promo[a-z]*|impression[a-z]*|clicktrack[a-z]*|tracker)([^a-z0-9]|$)/i;

  var AD_SEL = [
    "ins.adsbygoogle", ".adsbygoogle", ".ad-slot", ".adslot", ".ad-banner",
    ".ad-container", ".ad-wrapper", ".ad-holder", ".ad-placeholder", ".ad-box",
    ".ad-zone", ".ad-frame", ".ad-iframe", ".advert", ".advertisement",
    ".advertising", ".adv-block", ".banner-ad", ".ads-container",
    ".ads-wrapper", ".ads-block", ".sponsored-block", ".native-ad",
    ".popup-ad", ".popunder-ad", ".overlay-ad", ".sticky-ad", ".sticky-banner",
    ".floating-ad", ".floating-banner", "[data-ad]", "[data-ad-slot]",
    "[data-ad-client]", "[data-google-query-id]", "[id^='div-gpt-ad']",
    "[id*='aswift']", "[class*='ad-slot']", "[class*='ad-banner']",
    "[class*='ad-container']", "[class*='advertisement']",
    "[class*='popunder']", "[class*='sticky-ad']", "[class*='floating-ad']",
    "[class*='overlay-ad']", "[class*='popup-ad']"
  ].join(",");

  /* Never remove anything that carries real playback. */
  var KEEP = "video,audio,canvas,[allowfullscreen],[class*='player'],[id*='player']";

  function signature(el) {
    var c = el.className;
    if (c && c.baseVal !== undefined) c = c.baseVal; /* SVG */
    return ((el.id || "") + " " + (c || "") + " " +
            (el.getAttribute("aria-label") || "") + " " +
            (el.getAttribute("data-ad") || "") + " " +
            (el.getAttribute("title") || "")).toLowerCase();
  }

  function adShaped(el) {
    try {
      return AD_WORD.test(signature(el));
    } catch (e) {
      return false;
    }
  }

  function matchesAdSelector(el) {
    try {
      return !!(el.matches && el.matches(AD_SEL));
    } catch (e) {
      return false;
    }
  }

  function holdsPlayback(el) {
    try {
      return !!el.querySelector(KEEP);
    } catch (e) {
      return true; /* play safe */
    }
  }

  /* The stylesheet above collapses anything that looks like an ad slot to zero
   * height. That is right for a banner and wrong for a wrapper the player only
   * happened to name like one, so any box that carries the video gets its
   * dimensions back as inline styles, which win over the sheet. */
  function protectPlayers() {
    var nodes;
    try { nodes = document.querySelectorAll(KEEP); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i].parentElement;
      var hops = 0;
      while (el && el !== document.body && el !== document.documentElement && hops++ < 6) {
        try {
          if (matchesAdSelector(el) || adShaped(el)) {
            el.style.setProperty("display", "block", "important");
            el.style.setProperty("height", "auto", "important");
            el.style.setProperty("max-height", "none", "important");
            el.style.setProperty("min-height", "0", "important");
            el.style.setProperty("width", "auto", "important");
            el.style.setProperty("max-width", "none", "important");
            el.style.setProperty("overflow", "visible", "important");
            el.style.setProperty("margin", "0", "important");
            el.style.setProperty("padding", "0", "important");
          }
        } catch (e) { /* ignore */ }
        el = el.parentElement;
      }
    }
  }

  function remove(el) {
    if (!el || !el.parentNode) return;
    try {
      el.parentNode.removeChild(el);
    } catch (e) {
      hide(el);
    }
  }

  function hide(el) {
    if (!el || !el.style) return;
    try {
      el.style.setProperty("display", "none", "important");
      el.style.setProperty("visibility", "hidden", "important");
      el.style.setProperty("pointer-events", "none", "important");
    } catch (e) { /* ignore */ }
  }

  /* ---------------------------------------------------------------- sweep */

  function stripNewWindowTargets() {
    if (!CW.popups) return;
    try {
      var nodes = document.querySelectorAll("a[target]");
      for (var i = 0; i < nodes.length && i < 600; i++) {
        nodes[i].removeAttribute("target");
      }
    } catch (e) { /* ignore */ }
  }

  /* True when a box sits hard against an edge of the viewport. */
  function hugsEdge(r, vw, vh) {
    var tol = Math.max(12, Math.min(vw, vh) * 0.03);
    return r.top <= tol || r.left <= tol ||
           (vw - (r.left + r.width)) <= tol ||
           (vh - (r.top + r.height)) <= tol;
  }

  function inspectLayer(el, vw, vh, depth) {
    if (!el || el.nodeType !== 1) return;
    var tag = el.tagName;
    if (tag === "VIDEO" || tag === "AUDIO" || tag === "CANVAS" ||
        tag === "TEMPLATE" || tag === "HEAD" || tag === "STYLE" ||
        el.id === "cinewave-css") {
      return;
    }

    var cs;
    try { cs = window.getComputedStyle(el); } catch (e) { return; }
    if (!cs || cs.display === "none" || cs.visibility === "hidden") return;

    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return; }

    var fixed = (cs.position === "fixed" || cs.position === "sticky");
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z)) z = 0;

    /* An ad-shaped layer floating above the page. */
    if ((adShaped(el) || matchesAdSelector(el)) && (fixed || z > 40) && !holdsPlayback(el)) {
      remove(el);
      return;
    }

    /* A blank, full-page, very high z-index layer sitting on top of the
     * content: an overlay-ad backdrop or a click hijacker. It must really be
     * empty - no picture, no control, almost no text - before it is dropped. */
    if (fixed && z >= 800 && vw > 0 && vh > 0 &&
        r.width >= vw * 0.75 && r.height >= vh * 0.4 &&
        !holdsPlayback(el) && !el.querySelector("img,picture,svg")) {
      var text = "";
      try { text = (el.textContent || "").trim(); } catch (e) { text = ""; }
      if (text.length < 12) {
        remove(el);
        return;
      }
    }

    /* A floating banner clinging to an edge of the screen: short and wide, or
     * tall and narrow, sitting well above the page. Kept apart from the rule
     * above because a banner almost always carries a picture, and kept narrow
     * on purpose - it must hug an edge, float high above the content and hold
     * no video - so that a site header or a player is never caught. */
    if (fixed && z >= 500 && vw > 0 && vh > 0 && !holdsPlayback(el) &&
        el.tagName !== "BODY" && hugsEdge(r, vw, vh)) {
      var maxBand = Math.max(96, vh * 0.2);
      var maxStrip = Math.max(96, vw * 0.2);
      var banner = (r.width >= vw * 0.2 && r.height <= maxBand) ||
                   (r.height >= vh * 0.2 && r.width <= maxStrip);
      if (banner && el.tagName !== "NAV" && !el.querySelector("video,iframe")) {
        remove(el);
        return;
      }
    }

    if (depth < 2 && el.children) {
      for (var i = 0; i < el.children.length; i++) {
        inspectLayer(el.children[i], vw, vh, depth + 1);
      }
    }
  }

  CW.sweep = function () {
    if (!CW.enabled) return;
    if (CW.fullscreen || pageIsFullscreen()) return;
    installCss();
    protectPlayers();

    var doc = document;
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;

    /* 1. frames and images whose host the native layer already blocked. The
     *    blocked response renders as an empty box; take the box out too. */
    var media = doc.querySelectorAll("iframe,embed,object,ins,img");
    for (var i = 0; i < media.length; i++) {
      var el = media[i];
      var src = el.getAttribute("src") || el.getAttribute("data") ||
                el.getAttribute("data-src") || el.getAttribute("data-lazy-src") || "";
      if (src && hostBlocked(hostOf(src))) {
        var parent = el.parentNode;
        if (parent && parent.tagName !== "BODY" && parent.children &&
            parent.children.length === 1 && !holdsPlayback(parent)) {
          remove(parent);
        } else {
          remove(el);
        }
      }
    }

    /* 2. anything that plainly looks like an ad slot */
    var slots = doc.querySelectorAll(AD_SEL);
    for (var j = 0; j < slots.length; j++) {
      if (!holdsPlayback(slots[j])) remove(slots[j]);
    }

    /* 3. overlay / sticky / full-page ad layers, shallow scan only */
    var roots = doc.body ? doc.body.children : [];
    for (var k = 0; k < roots.length; k++) inspectLayer(roots[k], vw, vh, 0);
  };

  /* ------------------------------------------------------------- mutation */

  function scheduleSweep() {
    if (CW.__queued) return;
    CW.__queued = true;
    setTimeout(function () {
      CW.__queued = false;
      CW.sweep();
    }, 150);
  }

  try {
    if (window.MutationObserver && document.documentElement) {
      new MutationObserver(scheduleSweep).observe(document.documentElement, {
        childList: true,
        subtree: true
      });
    }
  } catch (e) { /* ignore */ }

  /* Ads that appear on a timer (5s, 15s, ...) need a watchdog. */
  var ticks = 0;
  var watchdog = setInterval(function () {
    CW.sweep();
    stripNewWindowTargets();
    if (++ticks > 40 || (document.readyState === "complete" && ticks > 20)) {
      clearInterval(watchdog);
    }
  }, 1500);

  /* ------------------------------------------------------------ popunders */

  function makeStub() {
    var noop = function () {};
    return {
      closed: true,
      opener: null,
      focus: noop,
      blur: noop,
      close: noop,
      print: noop,
      moveTo: noop,
      resizeTo: noop,
      postMessage: noop,
      alert: noop,
      document: { write: noop, writeln: noop, close: noop, open: noop },
      location: { href: "", replace: noop, assign: noop }
    };
  }

  if (CW.popups) {
    try {
      var stub = makeStub();
      window.open = function () {
        CW.blockedPopups = (CW.blockedPopups || 0) + 1;
        return stub;
      };
    } catch (e) { /* ignore */ }
  }

  /* Click guard: stop a click that would leave for a blocked ad host or that
   * lands inside a known ad slot. A click that happens while the video is
   * fullscreen is left alone, because that is where the player's own controls
   * live and swallowing one leaves a button that does nothing. */
  try {
    document.addEventListener("click", function (ev) {
      if (!CW.enabled && !CW.popups) return;
      if (CW.fullscreen || pageIsFullscreen()) return;
      var n = ev.target;
      var hops = 0;
      while (n && n.nodeType === 1 && hops++ < 8) {
        if (CW.enabled && n.tagName === "A") {
          var href = n.getAttribute("href") || "";
          if (href && hostBlocked(hostOf(href))) {
            ev.preventDefault();
            ev.stopImmediatePropagation();
            return;
          }
        }
        if (CW.enabled && matchesAdSelector(n) && !holdsPlayback(n)) {
          ev.preventDefault();
          ev.stopImmediatePropagation();
          hide(n);
          return;
        }
        n = n.parentNode;
      }
    }, true);
  } catch (e) { /* ignore */ }

  /* ---------------------------------------------------------------- start */

  CW.sweep();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      installCss();
      CW.sweep();
    }, { once: true });
  }
})();
