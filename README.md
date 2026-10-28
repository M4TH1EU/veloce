# Veloce

An independent Android car-projection client focused on a simple, polished experience.
Android 14+, with no Google Play services or root dependency.

## Current development build

- USB accessory and manually initiated wireless projection transports.
- TLS authentication using a one-time download of the public aasdk identity.
- Display discovery, H.264 video projection, touch, and directional input.
- Car-screen launcher with discovered app tiles and active media controls.
- The same car UI in a real virtual-display preview with preset/custom resolution and DPI.
- Phone-side setup, connection status, disconnect action, and shareable local diagnostics.

Physical car compatibility is awaiting validation. The implementation has been
reviewed against public protocol contracts and Android API sources. Desktop tests
simulate a head unit; they do not establish that a car accepts the identity or that
Android renders and encodes the launcher correctly.

Opening navigation/templated app interfaces, media-library browsing, and forwarded
audio are subsequent steps. Audio tiles can control an already active media session;
other tiles explain that their app host is still being implemented.

## Build

Open this directory in Android Studio and use its bundled JDK for Gradle.
Install Android SDK Platform 37 and use a device running Android 14 or later.
Android Studio can create `local.properties` with your SDK location.

```sh
./gradlew :app:assembleDebug
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release builds are unsigned until the project's signing setup is chosen.

## First car test

1. Install the debug APK as an update and open Veloce.
2. Tap **Set up car certificate** while internet access is available. The downloaded
   certificate/key are checksum-verified and stored in the app's no-backup directory.
3. Try **Car preview** first. Select a preset or enter width, height, and density.
   The preview renders into an actual private virtual display at those dimensions.
4. For USB, connect the car's Android Auto USB port and select Veloce in Android's
   accessory chooser. If another app owns the accessory, clear its USB default.
   **Connect USB** can retry an already attached accessory.
5. For wireless, pair the phone with the car in Android settings first. Enable
   Bluetooth/Wi-Fi, tap **Wireless**, grant nearby/local-network access, and choose
   the car. Accept Android's Wi-Fi connection prompt. Some head units initiate the
   Bluetooth bootstrap themselves: reconnect the phone from the car's Bluetooth
   menu if Veloce displays a waiting message.
6. The launcher should appear once the car grants display focus. Try scrolling the
   app grid, Home, directional controls, and **Car** to return to the native screen.
7. If setup fails, use **Share connection log** and include the phone/OS and head unit.

Also test native-screen return and re-entry, phone rotation while previewing,
disconnect during setup, repeated reconnects, and locking the phone. In particular,
private-display visibility under keyguard and screen-off power behavior are still
unverified. Start the first projection test with the phone unlocked.

Optional media controls require notification access. Start playback in the media
app first, then use the launcher's Now playing page. Audio forwarding over the
projection link is not enabled in this build.

Notification contents are not read by Veloce. The listener is used to obtain active
media sessions. It does not bypass an app's library-access or playback-capture policy.

## Verified scope and limitations

- 23 protocol tests cover framing, real JDK TLS, channel negotiation, fragmented
  video payloads, plaintext/encrypted keepalive, acknowledgement backpressure,
  focus restart, rejected input binding, and car-initiated shutdown.
- Two Android-module JVM tests cover touch-coordinate scaling and resource cleanup.
  They do not exercise Android framework objects.
- Projection uses the main H.264 display, supported standard resolutions up to
  1920 × 1080 (or 1080 × 1920), and the offered 30/60 fps configuration. Encoder
  support is device-dependent; automatic renegotiation after codec failure is absent.
- Touch is scaled from the advertised main touchscreen to the visible video area.
  Head-unit margin conventions, D-pad focus, and rotary traversal need car testing.
- Wireless supports an IPv4 car endpoint and open/WPA/WPA2 personal Wi-Fi.
  Bluetooth bootstrap version fields are only partially described publicly;
  real head-unit interoperability is unverified.
- **Disconnect** closes the transport promptly. Car-initiated shutdown is acknowledged;
  phone-initiated protocol goodbye negotiation is not implemented.

## Structure

- `app`: phone setup, transports/session service, virtual-display rendering,
  hardware encoding, car launcher, car-service discovery, and active media integration.
- `protocol`: framing, protobuf decoding, TLS, discovery, channel negotiation,
  bounded video flow control, and protocol tests without Android dependencies.
- [Foundation brief](FOUNDATION.md): agreed scope, constraints, and primary references.

## License

License selection is pending the project owner's decision.
