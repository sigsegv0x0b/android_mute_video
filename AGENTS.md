# Mute Video - Android App

## Build

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Build Environment (Termux on aarch64/arm64)

### Tools
- JDK 21 (openjdk-21)
- Kotlin 2.3.21
- Gradle 9.5.1
- Android SDK at `~/android-sdk` with platforms 33-36
- Android NDK available via sdkmanager but not required

### Critical: aapt2 Architecture

AGP bundles an **x86_64 aapt2** for Linux, which does NOT run on aarch64 Termux. The file `gradle.properties` overrides it:

```
android.aapt2FromMavenOverride=/data/data/com.termux/files/home/android-sdk/build-tools/34.0.4/aapt2
```

This aapt2 is aarch64 (statically linked for Android 34). It cannot load platform jars ≥35, so `compileSdk` must be **34** (not 35 or 36).

### Kotlin Version Compatibility

Kotlin 2.3.21 deprecated `kotlinOptions { jvmTarget = "..." }`. Use the `compilerOptions` DSL instead:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

### Library Versions

All dependencies must be compatible with compileSdk 34:

| Library | Version |
|---------|---------|
| core-ktx | 1.12.0 |
| appcompat | 1.6.1 |
| constraintlayout | 2.1.4 |
| activity-ktx | 1.8.2 |
| lifecycle-runtime-ktx | 2.7.0 |

## App Architecture

### Flow
1. **Mute Video**: User taps "Mute Video" → SAF file picker → `VideoMuter.muteVideo()` → strips audio tracks → saves to Downloads
2. **Trim Video**: User taps "Trim Video" → SAF file picker → file copied to cache → `TrimActivity` opens with preview/scrubber → user marks In/Out points → `VideoTrimmer.trimVideo()` crops between timestamps → saves to Downloads

### How Muting Works
Uses Android's `MediaExtractor` + `MediaMuxer` — no FFmpeg needed:
1. `MediaExtractor` reads the video file track-by-track
2. Only the **video track** is selected (audio/subtitle tracks skipped)
3. `MediaMuxer` writes the video packets as-is to a new MP4 (stream copy, no decode/re-encode)
4. Output is saved to `MediaStore.Downloads` with filename `Muted_Video_<timestamp>.mp4`

### How Trimming Works
Same stream-copy approach (no decode/re-encode):
1. `MediaExtractor` discovers all video + audio tracks
2. For each track: `seekTo(startTimeUs)`, reads samples until `endTimeUs`, writes with adjusted PTS (`pts - startTimeUs`)
3. Output saved to `MediaStore.Downloads` as `Trimmed_Video_<timestamp>.mp4`

### Key classes
- **MainActivity.kt** — Single activity, Trim Video + Mute Video buttons, SAF file pickers
- **TrimActivity.kt** — Trim UI: `VideoView` preview, play/pause, `SeekBar` scrubber, Mark In/Out buttons with positioned markers, Trim button
- **VideoMuter.kt** — Core mute logic: `copyUriToFile()`, `MediaExtractor`/`MediaMuxer` loop, `saveToDownloads()`
- **VideoTrimmer.kt** — Core trim logic: same as muter but copies all tracks between in/out timestamps

### Key layouts
- `activity_main.xml` — Two buttons (Trim Video, Mute Video) + log scroll
- `activity_trim.xml` — VideoView in FrameLayout, play button, time label, SeekBar with in/out marker overlays, Mark In/Out buttons, Trim button, log scroll
