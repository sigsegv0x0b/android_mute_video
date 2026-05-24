# Mute Video / Trim Video

A native Android app that mutes (strips audio) or trims video files without re-encoding. Uses Android's `MediaExtractor` + `MediaMuxer` APIs for fast, lossless stream copy.

## Features

### Mute Video
1. Tap **Mute Video** — opens a system file picker for `video/*` files
2. Select a video — copied to cache and processed
3. `MediaExtractor` reads all tracks, but only the **video track** is selected
4. `MediaMuxer` writes the video packets as-is to a new MP4 (no decode/re-encode)
5. Output saved to `Downloads/Muted_Video_<timestamp>.mp4`

### Trim Video
1. Tap **Trim Video** — opens a system file picker for `video/*` files
2. Select a video — copied to cache, opens trim view with preview
3. Use the scrubber to navigate, tap **Mark In** / **Mark Out** to set trim points
4. Tap **Trim Video** — extracts all tracks between the in/out timestamps
5. Output saved to `Downloads/Trimmed_Video_<timestamp>.mp4`

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
| `MainActivity.kt` | Main activity with Trim/Mute buttons + SAF file pickers |
| `TrimActivity.kt` | Trim UI: VideoView preview, play/pause, SeekBar scrubber, Mark In/Out, Trim button |
| `VideoMuter.kt` | Core mute: `copyUriToFile()`, `MediaExtractor`/`MediaMuxer` loop, `saveToDownloads()` |
| `VideoTrimmer.kt` | Core trim: same approach but copies all tracks between in/out timestamps |

## License

MIT
