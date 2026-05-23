# Mute Video

A native Android app that removes audio from video files without re-encoding. Uses Android's `MediaExtractor` + `MediaMuxer` APIs for fast, lossless stream copy.

## How it works

1. Tap **Mute Video** — opens a system file picker for `video/*` files
2. Select a video — the app copies it to cache and processes it
3. `MediaExtractor` reads all tracks, but only the **video track** is selected
4. `MediaMuxer` writes the video packets as-is to a new MP4 (no decode/re-encode)
5. Audio/subtitle tracks are dropped → effectively muted
6. Output is saved to `Downloads/Muted_Video_<timestamp>.mp4`

No FFmpeg, no external binaries, no extra permissions.

## Build

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### Building on Termux (aarch64)

AGP bundles an x86_64 `aapt2` for Linux, which won't run on aarch64. Set `gradle.properties`:

```properties
android.aapt2FromMavenOverride=/path/to/your/aarch64/aapt2
```

Use `compileSdk = 34` — the aarch64 aapt2 from build-tools 34.0.4 cannot load platform jars ≥35.

## Architecture

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Single activity with mute button + SAF file picker |
| `VideoMuter.kt` | Core: `copyUriToFile()`, `MediaExtractor`/`MediaMuxer` loop, `saveToDownloads()` |

## License

MIT
