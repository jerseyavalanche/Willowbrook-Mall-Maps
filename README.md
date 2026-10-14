# RoadNav Alpha — independent Waze-style road navigation experiment

**This branch contains the standalone RoadNav Android app.** It does not change RuthMap Afterdark (which remains on the repository's main branch). The application ID is `org.ruthroad.alpha`.

## Working slice targeted in v0.1

- Native MapLibre street map using OpenFreeMap / OpenStreetMap.
- Opt-in live Android GPS location and recentering.
- Long-press a destination on the map; tap START and map point to pick a manual start when GPS is unavailable.
- OSRM-compatible driving route with mapped route geometry, ETA, distance, first-step preview and limited foreground text-to-speech.
- Local incident pins only. Not crowdsourced or published.
- Configurable HTTPS OSRM server in MORE; default demo service must only be used for low-volume testing.

## Hard limits

**Not Waze yet.** No live traffic, validated navigation, auto road closures, community incident network, map editor, search, Android Auto, CarPlay, offline maps/routing, or voice guidance tested on a real car. Requires mobile internet for tiles and routing. OSRM response is not live traffic information. Do not operate the app while driving.

**Privacy**: GPS stays on the device except route requests, which include the selected start/end coordinates to the chosen routing service. Location authorization is requested at runtime. Reports are saved in private Android preferences.

## Build

Android 35 SDK, Gradle 8.9, Java 17. Build with `gradle :app:assembleDebug`, or download the GitHub Actions APK artifact/release tagged `roadnav-v0.1-alpha`. Debug-signed alpha installer.

## Components and attribution

MapLibre Native (BSD-2-Clause), OpenStreetMap contributors (ODbL), OpenFreeMap, OSRM (BSD-2-Clause). Respect each service's operational usage policy. A dedicated standalone repository is recommended before broader development; this branch is an isolated build staging area because the GitHub connection cannot create new repositories.
