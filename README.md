# Neon Orbits (Android)

Native Kotlin port of the neon four-body gravity sim (double-binary start, intruder/collision/rebirth).
No AndroidX dependencies: plain Activity + custom View, minSdk 26 (Android 8.0), target 35.

## Install (no Android Studio)
Every push to `main` builds a signed APK via GitHub Actions and publishes it as a release.
On the phone, open https://github.com/x509cert/NeonOrbits/releases in **Chrome** (not an in-app browser),
tap `NeonOrbits.apk`, then Open -> Install. Builds share one signing key (repo secrets
`KEYSTORE_B64` / `KEYSTORE_PASSWORD`), so updates install over the previous version.

## Build & install (Android Studio)
1. Android Studio -> Open -> this folder. Let Gradle sync (accept any AGP upgrade prompt).
2. Phone: Settings > About phone > tap "Build number" 7x -> Developer options -> enable USB debugging.
3. Plug in via USB, accept the RSA prompt on the phone, pick the device in the toolbar, press Run.

Or sideload: Build > Build App Bundle(s)/APK(s) > Build APK(s) -> app/build/outputs/apk/debug/app-debug.apk,
copy it to the phone and open it (allow "Install unknown apps" for your file manager).
CLI: `./gradlew installDebug` with the phone connected, or `adb install app-debug.apk`.

## Code
- Simulation.kt  physics (velocity Verlet, softened G=1), bond timers, intruder, explosion/rebirth
- OrbitView.kt   rendering: sky -> quarter-res persistent glow layer (GPU blur on Android 12+) -> additive main layer
- MainActivity.kt immersive fullscreen, keep-screen-on, pause/resume
