# Veloce

An independent Android car-projection client for Android 14+, without Google Play
services or root. USB and wireless connections share a launcher, AndroidX car-app
host, media browser, and playback-audio forwarding.

Veloce is experimental, not an official Android Auto client or a fully conformant
Car App Library host. Compatibility depends on the phone, head unit, and apps.

## Build

Open the project in Android Studio with Android SDK Platform 37 installed.
Gradle uses JDK 25; application and protocol bytecode target Java 17.

```sh
./gradlew :app:assembleDebug
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
`:app:assembleRelease` builds an optimized, unsigned APK; sign it with your own key
before distribution. Keep the same signing key for subsequent updates.

## Connect

1. Open Veloce and choose **Set up car certificate** with internet access available.
   The public aasdk identity is checksum-verified and stored outside backups.
2. **Car preview** opens the launcher on the phone at a selectable resolution/DPI.
3. **USB:** connect to the car's Android Auto port and choose Veloce in Android's
   accessory chooser. Clear another app's USB default if it owns the connection.
4. **Wireless:** pair the phone with the car or wireless adapter in Android settings,
   enable Bluetooth/Wi-Fi, then choose **Wireless** and accept the permission and
   Wi-Fi prompts. If the car initiates startup, reconnect from its Bluetooth menu.
5. Optionally enable **wireless auto-connect** for the selected device through
   Android's companion-device setup. Audio sharing still requires authorization.

**Car** returns to the head unit's native interface. **Disconnect** ends the session.
For troubleshooting, use **Share connection log** and include phone/OS, head-unit,
adapter, and app versions. Logs are local until shared; review them before posting.

## Apps and audio

- Compatible AndroidX car apps render their templates and map surfaces in Veloce.
  Home/music switching retains the current navigation session.
- Media apps provide folders and playback controls through Media3 or legacy browser
  services. If browsing is refused, grant notification access, start playback on the
  phone, and use **Now playing**.
- **Audio through Veloce** forwards eligible playback over USB or Wi-Fi after Android
  consent. Turn it off while disconnected to use Android's normal Bluetooth output.
- Optional notification access also enables an in-memory inbox and text banners.
  It does not grant library or audio-capture access. **Hide** only affects Veloce.

See [app compatibility](docs/APP_COMPATIBILITY.md) and [audio setup](docs/AUDIO.md).

## Limitations

The car-app host supports common list, grid, pane, message, search, tab, and navigation
templates up to Car App API 7, with fixed night mode. Microphone/assistant input,
car hardware APIs, cluster forwarding, notification replies, and read-aloud are absent.
Parked-only actions have no vehicle-status gate; configure and interact while parked.
Navigation speech and calls are separate from playback capture. Projection remains
subject to Android's lock-screen and power policies.

## Project

- `app/`: Android UI, transports, projection, app hosting, media, and notifications.
- `protocol/`: Android-independent framing, TLS, and projection session engine.
- [Architecture and references](docs/ARCHITECTURE.md).

License selection is pending. No open-source license is granted by this repository yet.
