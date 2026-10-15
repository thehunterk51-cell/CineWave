# CineWave Android — v1.2.0
English-language home screen for Android phones, Android TV and TV boxes.

## Features
- TV launcher support and large remote-friendly buttons.
- Toggleable basic WebView ad/tracker host blocking.
- Automatic update check on startup and manual check on the home screen.
- Update check reads version.json from main and opens the configured release page if a newer version exists.
- GitHub Actions builds a debug APK.

## Limitations
- Ad blocking uses a small host list and cannot guarantee removal of every ad or popup.
- Blocking may affect websites; test video playback on target devices.
- Watch later is informational only; persistent bookmarks are not implemented.
- The update checker opens a release page. Android requires user approval to install; publish an APK release and keep version.json current.
- No DRM bypass or protected-video downloading is included.

## Build
Open Actions and run Build CineWave APK. Download the CineWave-APK artifact after a successful run.