# CineWave Android — v1.4.0

WebView home screen for Android phones, Android TV and TV boxes.

## Features

- TV launcher support and large remote-friendly buttons.
- **Working fullscreen.** The player is given a real fullscreen surface
  (`onShowCustomView`), with immersive system bars, landscape lock and a
  keep-awake flag. The in-page ad guard stands down while the video is
  fullscreen, so the fullscreen layer is never mistaken for an ad backdrop.
- **Save a film.** Download the stream the player is using, optionally fetch a
  subtitle, and merge the subtitle into the video file without re-encoding.
- **Layered ad blocking** (see below): 9,884 network rules, 29 rotating-brand
  rules, 70 playback-safe exceptions, plus an in-page cosmetic filter.
- **Popup and redirect protection** as a separate switch: `window.open` is
  neutralised, new windows are dropped, `intent://` / `market://` / `tel:` /
  `mailto:` escapes are refused, and automatic cross-site redirects that no one
  clicked are cancelled.
- **Overlay removal:** sticky, floating and full-page ad layers, ad backdrops
  and click-hijack layers are removed before and after they appear.
- **Self-updating blocklist:** the app refreshes its own rule file from this
  repository (at most once every 3 days) and falls back to the bundled copy.
- Live counters on the home screen and in the browser bar.
- Automatic update check on startup and manual check on the home screen.
- GitHub Actions builds a debug APK.

## How the blocking works

Five layers run together:

| Layer | What it does |
| --- | --- |
| `CORE_BLOCK` seed in `MainActivity` | ~35 always-on rules, active before the big list finishes loading |
| Host rules (`assets/adblock_hosts.txt`) | 9,884 ad/tracker/popunder domains, matched on the host and every parent domain |
| Brand rules (`*.label`) | Blocks a whole brand across TLDs (`*.melbet`, `*.1xbet`, `*.stake`, …) |
| Native request filtering | `shouldInterceptRequest` answers blocked hosts with an empty response; the main frame gets a "Blocked" page instead of a blank screen |
| Cosmetic filter (`assets/adblock_cosmetic.css` + `adblock_cosmetic.js`) | Hides ad slots, removes blocked frames/images and overlay layers, keeps watching the DOM |

`@@` exception rules are checked before every block rule, so the two catalogues
and the shared infrastructure / video-embed hosts in the list are never blocked.
That is what keeps playback working. The in-page guard also refuses to touch
anything containing `video`, `[allowfullscreen]` or a `player` class.

## Saving a film

**Save video** in the browser bar reads the page, lists the streams and
subtitles the player exposes, and downloads the one you pick.

| Step | What happens |
| --- | --- |
| Find | `assets/media_probe.js` collects `<video>` sources, `<source>` children, `<track>` subtitles and — when the player has already decoded them — the live `TextTrackCues` |
| Download | `MediaFetcher` replays the WebView's `Cookie`, `User-Agent` and `Referer`, because stream hosts check them. Writes to a `.part` file first |
| Merge | `SubtitleParser` reads SRT or WebVTT, then `Tx3gMuxer` soft-muxes a 3GPP timed text track (`tx3g`) into the MP4 |
| Save | `MediaSaver` publishes through MediaStore into `Movies/CineWave` (or `Downloads/CineWave` for subtitles) |

**Merging is not re-encoding.** The audio and video payloads are copied byte for
byte, so quality is untouched and it finishes in seconds. The trick that keeps
it cheap is not moving anything: the original file is preserved and the new
samples plus a rebuilt index are appended. If the index sat at the end of the
file its space is reused; if it sat at the front its header becomes a `free`
box so no parser sees two `moov` boxes.

The result is the same file `ffmpeg -c:s mov_text` would produce, and it plays
in VLC, MPV, PotPlayer and any player that reads 3GPP timed text.

Files are written to the app's own folder first, because the merge needs a
second copy; they are published afterwards and the working copy is removed.
There is a free-space check before merging, because a large film needs roughly
twice its own size available at that moment.

## Limitations

- No blocklist removes every ad. First-party adverts served from a site's own
  domain are only handled cosmetically.
- Aggressive popup/redirect blocking can cancel a redirect you actually wanted.
  Turn the second switch off if a player stops opening.
- **Adaptive streams (HLS/DASH) cannot be saved.** They are a list of chunks
  rather than one file, so the picker marks them and refuses. Paste a direct
  `.mp4` link instead if you have one.
- Subtitle merging only applies to MP4. For any other container the subtitle is
  saved as a separate file instead, and the app says so.
- Fragmented MP4 (DASH-produced streams) cannot be merged.
- Watch later is informational only; persistent bookmarks are not implemented.
- The update checker opens a release page. Android requires user approval to
  install; publish an APK release and keep `version.json` current.
- No DRM bypass or protected-video downloading is included.
- The app is a browser shell for third-party sites; nothing about those sites'
  content or its legality is bundled or endorsed here.

## Maintaining the blocklist

Edit [`tools/build_blocklist.py`](tools/build_blocklist.py) and run it:

```bash
python3 tools/build_blocklist.py
```

It merges the AdAway default list (CC BY 3.0), Peter Lowe's ad server list and a
curated set, then rewrites `app/src/main/assets/adblock_hosts.txt`. Don't hand
edit the asset — the next run overwrites it.

The generated file carries the attribution that CC BY 3.0 requires, including
the licence link, a copyright notice and a statement that the file is a
modified version of the AdAway list:

| Source | Terms |
| --- | --- |
| AdAway default blocklist | CC BY 3.0 — Copyright (c) AdAway and contributors, <https://creativecommons.org/licenses/by/3.0/> |
| Peter Lowe's ad/tracker server list | Redistribution permitted, <https://pgl.yoyo.org/adservers/> |
| CineWave curated additions | Project owned, MIT |

The parser accepts almost any public list format, so you can also paste lines
into the asset directly:

```
domain.com      block this domain and all of its subdomains
*.label         block any host with this exact DNS label (TLD-rotating brands)
@@domain.com    never block this domain (exception rule)
0.0.0.0 x.com   hosts-file format, normalised on load
||x.com^        Adblock-Plus format, normalised on load
# comment
```

The app re-downloads the same file from `main` so a list update reaches devices
without a new APK.

## Limitations

- No blocklist removes every ad. First-party adverts served from a site's own
  domain are only handled cosmetically.
- Aggressive popup/redirect blocking can cancel a redirect you actually wanted.
  Turn the second switch off if a player stops opening.
- Watch later is informational only; persistent bookmarks are not implemented.
- The update checker opens a release page. Android requires user approval to
  install; publish an APK release and keep `version.json` current.
- No DRM bypass or protected-video downloading is included.
- The app is a browser shell for third-party sites; nothing about those sites'
  content or its legality is bundled or endorsed here.

## Build

```bash
gradle assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Or open Actions and run **Build CineWave APK**, then download the
`CineWave-APK` artifact. Artifacts expire, so a published
[release](https://github.com/thehunterk51-cell/CineWave/releases/latest) is the
durable place to download from.

## Licence

The code in this repository is MIT licensed — see [`LICENSE`](LICENSE).

One bundled file is not: `app/src/main/assets/adblock_hosts.txt` is derived
from lists under the Creative Commons Attribution 3.0 Unported licence. The
attribution those terms require is reproduced in the header of that file and
set out in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). No third-party
source code is bundled.
