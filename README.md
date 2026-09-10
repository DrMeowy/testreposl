# Moonbridge Android Client

The Android client for Moonbridge, a lightweight LAN remote desktop controller.

## Install

Download the latest APK from the repository's [Releases](https://github.com/DrMeowy/testreposl/releases) page and install it on an Android phone connected to the same Wi-Fi as the Moonbridge PC host.

Start the PC host, enter its address in the app (for example `192.168.1.24:47100`), and tap **CONNECT**. The Android client does not ask for a pairing code.

The app is a native Kotlin client. It connects directly to separate video, input, and audio channels, decodes H.264 on the tablet's hardware video path, sends touch/mouse input, plays host audio, and provides a keyboard control without loading a browser page. The PC host bundles FFmpeg and prefers a hardware H.264 encoder, with a low-latency CPU fallback.

If the app reports that the PC is unreachable, first open `http://PC_ADDRESS:47100` in the tablet's browser. If that also times out, allow Moonbridge through Windows Defender Firewall for **Private networks** and make sure the tablet is not on a guest Wi-Fi network. The Android client connects directly without a pairing code.

## Build locally

Open `android-client` in Android Studio, sync Gradle, and run the `app` configuration. The GitHub Actions workflow also builds a signed-debug APK and attaches it to every `v*` release tag.
