# Moonbridge Android Client

The Android client for Moonbridge, a lightweight LAN remote desktop controller.

## Install

Download the latest APK from the repository's [Releases](https://github.com/DrMeowy/testreposl/releases) page and install it on an Android phone connected to the same Wi-Fi as the Moonbridge PC host.

Start the PC host, enter its address in the app (for example `192.168.1.24:47100`), tap **OPEN**, then enter the six-digit pairing code shown on the PC.

The app is a native Kotlin shell around the Moonbridge client surface. The host currently serves the low-latency LAN session over WebSocket and does not require an online account.

## Build locally

Open `android-client` in Android Studio, sync Gradle, and run the `app` configuration. The GitHub Actions workflow also builds a signed-debug APK and attaches it to every `v*` release tag.
