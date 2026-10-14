# RuthMap Afterdark · Willowbrook (standalone Android)

Independent Android application for field mapping at Willowbrook Mall, Wayne NJ. Built as a clean-room v0.2 implementation based on the user's RuthMap Afterdark visual prototype. This repository does not modify RuthLab, Mallster, RuthChat, or any other app.

## Features

- Neon offline **schematic** two-floor Willowbrook reference map with named anchors.
- Bright position dot and cyan step-sensor walking trail, with tap-to-place start position.
- Opt-in Wi-Fi scan results and BLE advertisement discovery; users choose which observations to pin to their *estimated* observation location.
- Save surveys to Android Downloads as JSON (or app-specific storage on Android 8).
- Floor selector, replay, find a place, data count, zoom/pan.
- Location permissions requested only when scan or outside GPS is started. All data remains on-device.

**Accuracy notice:** The geometry is a hand-reconstructed illustrative schematic from an earlier screenshot, *not* a verified floor plan or georeferenced mapping dataset. Indoor GPS is not reliable; the current position comes from manual start-point selection plus estimated motion sensor steps. Signal pin coordinates represent **where detected**, not transmitter location. Validate and georeference real floor boundaries before navigating by this map.

## Build

Use the GitHub Actions APK build workflow, or Android SDK 35 / Java 17 with Gradle 8.9+: `gradle assembleDebug`. The built debug APK can be installed on Android 8 or newer. Use only open-source Android SDK platform components: no backend, no trackers, no network dependency at runtime.
