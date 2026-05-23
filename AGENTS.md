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
1. User taps "Mute Video" → SAF file picker (`ACTION_OPEN_DOCUMENT`, `video/*`)
2. Selected URI is passed to `VideoMuter.muteVideo()`
3. File is copied to cache, processed, result saved to Downloads via `MediaStore`

### How Muting Works
Uses Android's `MediaExtractor` + `MediaMuxer` — no FFmpeg needed:
1. `MediaExtractor` reads the video file track-by-track
2. Only the **video track** is selected (audio/subtitle tracks skipped)
3. `MediaMuxer` writes the video packets as-is to a new MP4 (stream copy, no decode/re-encode)
4. Output is saved to `MediaStore.Downloads` with filename `Muted_Video_<timestamp>.mp4`

### Key classes
- **MainActivity.kt** — Single activity, button + file picker + processing trigger
- **VideoMuter.kt** — Core: `copyUriToFile()`, `MediaExtractor`/`MediaMuxer` loop, `saveToDownloads()`
