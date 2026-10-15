#!/usr/bin/env python3
"""Regenerate app/src/main/assets/adblock_hosts.txt for CineWave.

The generated file is both:
  * the blocklist bundled inside the APK, and
  * the blocklist the app refreshes from
    https://raw.githubusercontent.com/thehunterk51-cell/CineWave/main/app/src/main/assets/adblock_hosts.txt

File syntax (parsed by com.cinewave.app.MainActivity.BlockList)
--------------------------------------------------------------
  domain.com        block domain.com and every subdomain of it
  *.label           block any host that has a DNS label exactly equal to "label"
                    (used for ad/redirect brands that rotate their TLD)
  @@domain.com      never block this domain or its subdomains (exception rule)
  # ... / ! ...     comment
  blank line        ignored
  hosts-format lines ("0.0.0.0 domain.com", "127.0.0.1 domain.com") and
  Adblock-Plus lines ("||domain.com^") are normalised automatically, so you can
  paste almost any public blocklist into this file.

Sources (both permit redistribution with attribution):
------------------------------------------------------
  * AdAway default blocklist - CC Attribution 3.0
    https://github.com/AdAway/adaway.github.io
  * Peter Lowe's ad/tracker server list - https://pgl.yoyo.org/adservers/
    ("Feel free to combine this list with yours or lists from other sites and
    put it up on the web")
  * CineWave curated additions (hand written, project-owned, no licence issue).

Run:  python3 tools/build_blocklist.py
"""

import ipaddress
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "adblock_hosts.txt")

SOURCES = [
    (
        "AdAway default blocklist (CC BY 3.0)",
        "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
    ),
    (
        "Peter Lowe's ad/tracker server list (pgl.yoyo.org)",
        "https://pgl.yoyo.org/adservers/serverlist.php"
        "?hostformat=nohtml&showintro=0&mimetype=text/plain",
    ),
]

# ---------------------------------------------------------------------------
# Curated additions: ad/popunder/redirect networks that show up as overlay and
# forced-redirect ads on streaming pages but are missing from the base lists.
# ---------------------------------------------------------------------------
CURATED_DOMAINS = [
    # --- ad exchanges / RTB / verification (overlay + interstitial ads) ---
    "2mdn.net", "360yield.com", "3lift.com", "33across.com", "adform.net",
    "adhigh.net", "adnium.com", "adnxs-simple.com", "adsafeprotected.com",
    "adscale.de", "advertising.com", "adzerk.net", "amazon-adsystem.com",
    "beeswaxio.com", "bidswitch.net", "bluekai.com", "casalemedia.com",
    "contextweb.com", "criteo.net", "districtm.io", "doubleverify.com",
    "gumgum.com", "idx.li", "imrworldwide.com", "indexexchange.com",
    "inmobi.com", "integralads.com", "lijit.com", "loopme.me", "media.net",
    "moatads.com", "mookie1.com", "openx.net", "pubmatic.com",
    "rubiconproject.com", "sharethrough.com", "smartadserver.com",
    "sovrn.com", "spotxchange.com", "springserve.com", "taboola.com",
    "teads.tv", "tremorhub.com", "triplelift.com", "undertone.com",
    "unrulymedia.com", "vidazoo.com", "yieldmo.com", "zedo.com",
    # --- popunder / popup / push ad networks ---
    "adcash.com", "adclickmedia.com", "adfoc.us", "admaven.com",
    "adnium.net", "adsterra.org", "adsterra.com", "bidvertiser.com", "clickadu.com",
    "evadav.com", "galaksion.com", "hilltopads.com", "hilltopads.org",
    "kadam.net", "linkvertise.com", "mgid.com", "monetag.com",
    "mytopoffers.com", "onclckbn.com", "onclckbn.net", "onclickads.net",
    "onclicksuper.com", "popads.com", "popmyads.com", "popunder.net",
    "profitableratecpm.com", "propellerads.tv", "pushnami.com",
    "recreativ.ru", "revcontent.com", "richpush.co", "shorte.st",
    "trafficstars.com", "trafficshop.com", "zeropark.com",
    # --- adult / low-quality networks frequently used for movie-site popunders ---
    "adnium.com", "exoclick.net", "exosrv.com", "juicyads.net",
    "trafficjunky.com", "trafficjunky.net", "trafficstars.net",
    # --- ad servers that live under a shared CDN and would otherwise be
    #     skipped by the allowlist's subdomain matching ---
    "ads.google.com", "adservice.google.com", "analytics.google.com",
    "pagead.l.google.com", "partnerad.l.google.com", "ads.youtube.com",
    "cloudflareinsights.com", "static.cloudflareinsights.com",
    "jioads.akamaized.net", "speee-ad.akamaized.net",
    "ntvcld-a.akamaihd.net", "middycdn-a.akamaihd.net",
    "inmobisdk-a.akamaihd.net", "ds-aksb-a.akamaihd.net",
    # --- forced-redirect / fake-player / fake-download gateways ---
    "cloudnestra.com", "clean-video.online", "hdplayer.site",
    "offerscloud.net", "streamsb.net", "vidsrc.me", "vidsrc.net",
    "vidsrc.to", "2embed.cc", "2embed.ru", "2embed.skin", "2embed.to",
    # --- download/install hijack pages seen on TV boxes ---
    "apkcombo.net", "apkmirror.site", "install-apk.com", "watch-free.tv",
    # --- ad-heavy link shorteners ---
    "adf.ly", "bc.vc", "clk.sh", "cuty.io", "fc.lc", "gplinks.co",
    "ouo.io", "shrinkme.io", "srt.am", "tinylink.net",
]

# Brands that rotate their top-level domain. Matched as a DNS label, so
# "melbet.anything" is blocked but "melbetplus.com" is not.
CURATED_LABEL_TOKENS = [
    "melbet", "1xbet", "1win", "22bet", "betano", "betwinner", "bet365",
    "linebet", "mostbet", "parimatch", "leonbets", "marathonbet", "pin-up",
    "stake", "vavada", "joycasino", "pokerdom", "betfair", "1xstavka",
    "casino-x", "playfortuna", "volna-casino", "sol-casino", "daddy-casino",
    "cat-casino", "legzo", "roxy-casino", "fresh-casino", "booi-casino",
]

# Domains that must NEVER be blocked: the sites the app opens, and the shared
# infrastructure / video-embed hosts whose blocking would break page rendering
# or playback. Anything listed here is stripped from the generated blocklist
# and emitted as an "@@" exception instead.
SAFE_DOMAINS = [
    # the two catalogues the app opens
    "azmovies.to", "noxx.gg",
    # pages break without these
    # NOTE: broad CDN apexes (cloudfront.net, akamaized.net, fastly.net,
    # akamaihd.net, google.com) are deliberately NOT listed. The app's
    # allowlist matches subdomains, so allowing an apex would also unblock the
    # ad servers hosted under it. Only list what the pages really need.
    "gstatic.com", "googleapis.com", "recaptcha.net", "hcaptcha.com",
    "cloudflare.com", "jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com",
    "jquery.com", "bootstrapcdn.com", "fontawesome.com", "bootcdn.net",
    "bunnycdn.com", "b-cdn.net",
    # player / embed hosts: allowlisted so the blockers cannot kill playback
    "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "ggpht.com",
    "dailymotion.com", "dmcdn.net", "vimeo.com", "vimeocdn.com", "vk.com",
    "vkvideo.ru", "ok.ru", "mycdn.me", "streamtape.com", "streamtape.net",
    "dood.so", "dood.yt", "doodstream.com", "ds2play.com", "dsvplay.com",
    "filemoon.sx", "filemoon.to", "moonstream.to", "streamsb.com",
    "vidmoly.to", "vidoza.net", "vidoza.org", "mp4upload.com",
    "upstream.to", "mixdrop.co", "mixdrop.to", "mixdrop.sx",
    "streamhide.com", "streamhide.to", "waaw.to", "netu.tv", "hqq.tv",
    "embed.su", "multiembed.mov", "smashystream.com", "vidcloud9.com",
    "vidplay.online", "vidcloud.co", "mega.nz", "uqload.io", "uqload.com",
    "solidfiles.com", "evoload.io", "streamlare.com", "fembed.com",
    "doodcdn.com", "doodcdn.to", "nanocosmos.de", "jetpack.wordpress.com",
]

LABEL_RE = re.compile(r"^[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?$")
DOMAIN_RE = re.compile(
    r"^(?!-)[a-z0-9\-]{1,63}(\.(?!-)[a-z0-9\-]{1,63})+$"
)


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "CineWave-blocklist-builder/1.0"})
    with urllib.request.urlopen(req, timeout=90) as resp:
        return resp.read().decode("utf-8", "replace")


def normalise_entries(text: str):
    """Yield normalised base domains from any hosts/ABP/plain list."""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!", "//", "[")):
            continue
        lowered = line.lower()

        # Adblock-Plus: ||domain.com^   (+ optional $options)
        if lowered.startswith("||"):
            candidate = lowered[2:].split("^")[0].split("$")[0].strip("/")
        # hosts file: 0.0.0.0 domain.com  /  127.0.0.1 domain.com  /  ::1 domain
        elif lowered.startswith(("0.0.0.0", "127.0.0.1", "::1", "255.255.255.255")):
            parts = lowered.split()
            if len(parts) < 2:
                continue
            candidate = parts[1]
        else:
            candidate = lowered.split()[0]

        candidate = candidate.strip().strip(".").rstrip("^")
        if not candidate or "." not in candidate:
            continue
        if "/" in candidate or "*" in candidate or "?" in candidate:
            continue
        try:
            ipaddress.ip_address(candidate)
            continue  # never ship raw IPs
        except ValueError:
            pass
        if not DOMAIN_RE.match(candidate):
            continue
        if len(candidate) > 190:
            continue
        yield candidate


def is_safe(domain: str) -> bool:
    """Exact match only.

    Dropping subdomains here would delete legitimate ad servers that happen to
    sit under an allowed domain (ads.google.com, jioads.akamaized.net, ...).
    Subdomains of an allowed domain are still never blocked, because the app
    checks its allowlist before every block rule at request time.
    """
    return domain in SAFE_DOMAINS


def main() -> int:
    domains = set()
    for name, url in SOURCES:
        try:
            text = fetch(url)
        except Exception as exc:  # keep the build working offline
            print(f"  ! skipped {name}: {exc}", file=sys.stderr)
            continue
        found = set(normalise_entries(text))
        print(f"  + {name}: {len(found)} domains")
        domains |= found

    curated = {d for d in CURATED_DOMAINS if LABEL_RE.match(d.split(".")[0])}
    domains |= curated
    print(f"  + CineWave curated list: {len(curated)} domains")

    dropped = sorted(d for d in domains if is_safe(d))
    domains -= set(dropped)
    tokens = sorted({t for t in CURATED_LABEL_TOKENS if LABEL_RE.match(t)})

    header = [
        "# CineWave ad / tracker / popup blocklist",
        "#",
        "# Generated by tools/build_blocklist.py - edit that script, not this file.",
        "# Syntax:  domain.com  |  *.label  |  @@allowed.com  |  # comment",
        "#",
        "# Sources (attribution):",
        "#   AdAway default blocklist - CC Attribution 3.0",
        "#   Peter Lowe's ad/tracker server list - https://pgl.yoyo.org/adservers/",
        "#   CineWave curated additions (project-owned).",
        "#",
        f"# Domains: {len(domains)}   rotating-brand labels: {len(tokens)}",
        "",
        "# ---- rotating ad / gambling redirect brands (matched as a DNS label) ----",
    ]

    body = [f"*.{t}" for t in tokens]
    body.append("")
    body.append("# ---- allowlist: never blocked, so pages and players keep working ----")
    body += [f"@@{d}" for d in sorted(SAFE_DOMAINS)]
    body.append("")
    body.append("# ---- blocked domains ----")
    body += sorted(domains)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(header + body) + "\n")

    size = os.path.getsize(OUT)
    print(f"wrote {os.path.relpath(OUT)}  {len(domains)} domains, "
          f"{len(tokens)} labels, {len(SAFE_DOMAINS)} exceptions, {size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
