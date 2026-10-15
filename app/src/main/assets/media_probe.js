/* CineWave media probe.
 *
 * Runs in the page the WebView is showing and reports back, as JSON, everything
 * needed to save what is playing: the addresses of the video files the player
 * has loaded, and the subtitles it offers.
 *
 * Three sources are checked, because sites use all three:
 *   1. <video> elements, including every <source> child
 *   2. <track> elements, which point at a .vtt or .srt file
 *   3. live TextTrackCues inside the player, which are serialised to WebVTT
 *      because there is no address to download
 *
 * Same-origin frames are searched too; a cross-origin frame is left alone,
 * since the page is not allowed to read it.
 */
(function () {
  "use strict";

  function abs(url) {
    if (!url) return "";
    try {
      return new URL(url, document.baseURI).href;
    } catch (e) {
      return url;
    }
  }

  function pad(n, width) {
    var s = String(n);
    while (s.length < width) s = "0" + s;
    return s;
  }

  function stamp(seconds) {
    var total = seconds || 0;
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = Math.floor(total % 60);
    var ms = Math.round((total - Math.floor(total)) * 1000);
    if (ms >= 1000) { ms = 0; s += 1; }
    return pad(h, 2) + ":" + pad(m, 2) + ":" + pad(s, 2) + "." + pad(ms, 3);
  }

  function textOf(node) {
    try {
      return ((node && node.textContent) || "").replace(/\s+/g, " ").trim();
    } catch (e) {
      return "";
    }
  }

  function describe(doc, out) {
    var seenStream = out.streams.reduce(function (acc, item) {
      acc[item.url] = true;
      return acc;
    }, {});
    var seenSub = out.subs.reduce(function (acc, item) {
      if (item.url) acc[item.url] = true;
      return acc;
    }, {});

    var vids = doc.querySelectorAll("video");
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];

      var current = abs(v.currentSrc || v.getAttribute("src"));
      if (current && !seenStream[current]) {
        seenStream[current] = true;
        out.streams.push({
          url: current,
          label: textOf(v) || ("Stream " + (out.streams.length + 1)),
          kind: "current"
        });
      }

      var sources = v.querySelectorAll("source");
      for (var j = 0; j < sources.length; j++) {
        var src = abs(sources[j].getAttribute("src") ||
                      sources[j].getAttribute("data-src"));
        if (!src || seenStream[src]) continue;
        seenStream[src] = true;
        out.streams.push({
          url: src,
          label: sources[j].getAttribute("label") ||
                 sources[j].getAttribute("data-res") ||
                 sources[j].getAttribute("title") || "Source " + (j + 1),
          kind: "source"
        });
      }
    }

    var tracks = doc.querySelectorAll("track");
    for (var k = 0; k < tracks.length; k++) {
      var t = tracks[k];
      var kind = (t.getAttribute("kind") || "subtitles").toLowerCase();
      if (kind === "chapters" || kind === "metadata") continue;
      var url = abs(t.getAttribute("src"));
      if (!url || seenSub[url]) continue;
      seenSub[url] = true;
      out.subs.push({
        url: url,
        label: t.getAttribute("label") || t.getAttribute("srclang") ||
               ("Subtitle " + (out.subs.length + 1)),
        lang: t.getAttribute("srclang") || "",
        inline: false
      });
    }

    /* Cues the player already decoded have no address, so ship them as text. */
    for (var m = 0; m < vids.length; m++) {
      var list = vids[m].textTracks;
      for (var n = 0; n < list.length; n++) {
        var track = list[n];
        if (track.kind === "chapters" || track.kind === "metadata") continue;
        var cues = null;
        try { cues = track.cues; } catch (e) { cues = null; }
        if (!cues || !cues.length) continue;

        var vtt = "WEBVTT\n\n";
        try {
          for (var c = 0; c < cues.length; c++) {
            vtt += stamp(cues[c].startTime) + " --> " + stamp(cues[c].endTime) + "\n";
            vtt += String(cues[c].text || "").replace(/<[^>]*>/g, "") + "\n\n";
          }
        } catch (e) { continue; }

        if (vtt.length > 1500000) continue;
        out.subs.push({
          url: "",
          label: (track.label || track.language || "Subtitle") + " (from the player)",
          lang: track.language || "",
          inline: true,
          vtt: vtt
        });
      }
    }
  }

  var out = { page: location.href, title: (document.title || "").trim(),
              streams: [], subs: [] };
  try { describe(document, out); } catch (e) { /* keep whatever was found */ }

  try {
    var frames = document.querySelectorAll("iframe,frame");
    for (var f = 0; f < frames.length && f < 8; f++) {
      var inner = null;
      try { inner = frames[f].contentDocument; } catch (e) { inner = null; }
      if (inner) { try { describe(inner, out); } catch (e2) { /* skip frame */ } }
    }
  } catch (e) { /* cross-origin frames are expected */ }

  return JSON.stringify(out);
})();