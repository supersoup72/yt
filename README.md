# YTApp

A YouTube client for Android (Samsung Galaxy S20 FE / arm64-v8a) powered by `yt-dlp`. No API keys required.

## Features

| Tab | Description |
|---|---|
| 🏠 Home | History-seeded recommendation feed |
| 🔍 Search | Search YouTube (20 results) |
| 📺 Subscriptions | Latest videos from subscribed channels |
| 📚 Library | Liked videos, watch history, downloads |

**Video card actions:**
- Tap → Play in built-in ExoPlayer (fullscreen landscape)
- ❤️ button → Like / unlike
- ⋮ button or long-press → Stats, Subscribe to channel, Download

## How yt-dlp is bundled

The `yt-dlp` ARM64 binary is **downloaded automatically** by GitHub Actions at build time from the [official yt-dlp releases](https://github.com/yt-dlp/yt-dlp/releases). It is placed in `app/src/main/assets/yt-dlp` before Gradle packages the APK.

On first app launch, the binary is copied from assets to the app's private `filesDir` and made executable. All video data (search, streams, metadata) is fetched by calling this binary via `ProcessBuilder`.

## Build via GitHub Actions

1. Push this repo to GitHub
2. Actions tab → **Build APK** workflow runs automatically
3. Download `YTApp-debug.apk` from the workflow's **Artifacts** section

## Build locally

```bash
# 1. Download yt-dlp binary
curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 \
     -o app/src/main/assets/yt-dlp
chmod +x app/src/main/assets/yt-dlp

# 2. Build
./gradlew assembleDebug

# APK output:
# app/build/outputs/apk/debug/app-debug.apk
```

## Install on S20 FE

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to the phone and install via Files app (enable "Install unknown apps" first).

## Requirements

- Android 8.0+ (minSdk 26)
- arm64-v8a device (S20 FE ✓)
- Internet connection
