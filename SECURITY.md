# Security Policy

## Reporting a vulnerability

Please report security issues privately rather than opening a public issue.
Use the repository's **Security → Report a vulnerability** form, which creates
a private advisory visible only to the maintainer.

Include what you did, what you expected, what happened, and the app version
(`Settings → About` is not present, so check the release you installed).

## Scope

CineWave is a WebView shell. It loads third-party pages and has no server
component, no account system and no analytics.

What is in scope:

- Remote code execution through the WebView or the in-page guard.
- Path traversal or arbitrary file access from the save/merge feature.
- Requests made with credentials the user did not intend to share.

What is not in scope:

- Ads that the blocklist misses. This is a best-effort filter, not a guarantee.
- Sites that behave badly inside a WebView.
- Claims about content on third-party sites. CineWave hosts no video, no
  subtitles and no media of any kind.

## Hardening notes for anyone auditing this

- Local and mixed-origin file access are disabled in the WebView.
- Third-party cookies are rejected; only the site's own session cookie is sent.
- `intent://`, `market://`, `tel:` and `mailto:` navigations are refused.
- The blocklist can only cause requests to be refused. It cannot inject
  content, since it is matched on host names and never rewritten into a page.
- `Set-Cookie`, redirects and file paths are parsed with fixed rules and are
  never passed to a shell.
- A save writes only to the app's own folder and to `Movies/CineWave` through
  MediaStore.