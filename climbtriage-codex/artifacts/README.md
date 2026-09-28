# Android test artifact

`ClimbTriage-0.1.0-debug.apk` is built from this source using the pinned Gradle/Android dependencies and official MediaPipe Lite task. See `build-manifest.json` for SHA-256 and test counts. The large APK is intentionally ignored by Git; rebuild from source when sharing the repository.

Install locally with Android's package installer or:

```powershell
.\.tools\android-sdk\platform-tools\adb.exe install -r artifacts\ClimbTriage-0.1.0-debug.apk
```

The APK is debug signed, requires Android 8/API 26 or later, and packages arm64-v8a, armeabi-v7a, x86 and x86_64 libraries. No connected device was available for installation or runtime validation. It contains a real pose model; it contains no climbing footage, provider key, mock detections or claimed benchmark results.

Use [the README walkthrough](../README.md) and [verification limits](../docs/VERIFICATION.md). For automatic hold proposals, a separately running local server/worker and a server-side fal credential are required. Manual holds and saved review are local.
