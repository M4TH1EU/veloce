# Veloce

An independent Android car-projection client focused on a simple, polished experience.
Android 14+, with no Google Play services or root dependency.

## Current development build

- USB accessory and wireless projection, including opt-in companion-device auto-connect.
- TLS authentication using a one-time download of the public aasdk identity.
- Display discovery, H.264 video projection, touch, and directional input.
- Car-screen launcher with discovered app tiles and active media controls.
- In-app AndroidX Car App Library host with template rendering and navigation map surfaces.
- Retained navigation sessions and paged media-library browsing with playback controls.
- The same car UI in a real virtual-display preview with preset/custom resolution and DPI.
- Phone-side setup, connection status, disconnect action, and shareable local diagnostics.
- Media playback capture forwarded through negotiated PCM/AAC audio sinks over USB or Wi-Fi.
- A basic direct-Bluetooth audio mode using Android's normal output routing.

The owner has tested wired and wireless projection and patched Waze rendering.
The new audio forwarding, automatic wireless startup, and remaining hardware
workflows still need device validation.
Desktop tests do not establish compatibility with a particular head unit or app build.

Templated app tiles open their AndroidX car interface in Veloce. Audio tiles
connect to the app's media library when permitted, with active-session controls
as a fallback. Media audio uses Android playback capture when the playing app permits it.

## Audio

**Audio through Veloce** is enabled by default. It uses the same media-audio path
over USB, native wireless, and wireless-to-USB adapters. With an adapter, choose
the adapter for wireless startup; the car's separate Bluetooth pairing can remain
in place for calls. Veloce does not assume the startup device is a Bluetooth speaker.

When the car offers a supported media sink, Veloce requests audio recording and
Android's screen-sharing authorization. The authorization is used for playback
audio only; no phone-screen capture display is created. A connected session keeps
capture through app changes. If the app was in the background during startup, open
its connection notification to enable audio. **Stop audio** releases capture;
**Enable audio** requests fresh authorization to start it again.

Veloce prefers 16-bit PCM and supports AAC-LC/ADTS sinks when required. It negotiates
the sample rate and channels, uses bounded acknowledgement windows, and requests
car media focus when captured sound begins. Permanent car focus loss pauses phone
media; transient interruption leaves Android's own playback-resume behavior intact.
Local media is muted during capture and restored at shutdown, including recovery
after a process restart. The exported log includes sink negotiation, focus, capture
silence, and car-reported underflows.

Playback capture is limited to eligible MEDIA/GAME/UNKNOWN audio in the same user
profile. Apps can prohibit it, in which case capture may contain silence. Navigation
speech, including Waze's native guidance, is deferred; no replacement directions
are generated. Cellular calls continue through the normal Bluetooth hands-free
connection, whose behavior with the specific adapter still needs testing.

To use direct Bluetooth media instead, disconnect and turn **Audio through Veloce**
off, then choose the real car in Android's output/Bluetooth settings. The projection
MEDIA sink stays closed. Veloce shows Android's anticipated media route; it cannot
force another app's player onto a particular Bluetooth device.

Optional setup commands, also shown in the audio section:

```sh
adb shell appops set ch.broillet.veloce PROJECT_MEDIA allow
adb shell pm grant ch.broillet.veloce android.permission.WRITE_SECURE_SETTINGS
```

The first preauthorizes sharing on supported Android builds. The second lets Veloce
temporarily lift Android's global screen-share notification protection during
audio capture and restore the previous setting afterward. Neither bypasses a media
app's capture policy. Normal consent works without these optional grants.

## Car app hosting (experimental)

The host is part of the main app, shared by car projection and **Car preview**.
No companion APK or test app is required. It uses the official AndroidX Car App
Library 1.7.0 contracts, negotiating a common Car App API level up to 7.

Implemented interfaces:

- Service binding, host handshake, lifecycle, invalidation, app actions, and Back.
- List, grid, pane, message, long-message, search, and tab templates.
- NavigationTemplate, MapWithContentTemplate, and the older MapTemplate,
  PlaceListNavigationTemplate, and RoutePreviewNavigationTemplate.
- TextureView-backed map surfaces, dimensions/DPI, visible/stable areas,
  tap/scroll/fling/pinch callbacks, and pan-mode actions.
- Navigation alerts, content limits, and on-screen errors with diagnostic logs.

This is a template subset, not full Car App API conformance. Sign-in is directed
to the phone; host-drawn POI maps, hardware APIs, microphone/voice input, cluster
forwarding, cross-app launches, and the global CarConnection provider are absent.
The host uses the same fixed night configuration as Veloce's UI. Parked-only
actions are forwarded without a vehicle-status check, as requested for this host. Search uses
Android's text input; a car-specific keyboard is not implemented.
Home and music pages keep the current templated app bound, pausing its UI and
releasing its map surface while hidden. An active route offers **Return to…**
and **Stop route** controls. Reopening the same app resumes the existing session.
Opening another templated app or disconnecting closes the old session and stops
its navigation. Background location/guidance still depends on the navigation
app's own implementation and permissions; locked-phone behavior is unverified.

Patched Waze/Google Maps builds must expose an exported AndroidX `CarAppService`
and accept Veloce's host identity. A Morphe host-validation patch alone does not
prove this; legacy/proprietary car interfaces are not supported by this host.
The owner has confirmed Waze rendering with the Morphe patch; other builds remain unverified.

[Veloce Patches](veloce-patches/README.md) is the standalone Morphe bundle project,
initially containing a Waze custom-host patch. The reported Waze manager-proxy
crash was a Veloce cast error, now fixed; the installed Waze build already passed
host validation, so update Veloce and test it before repatching Waze.

To test, open a patched map app in **Car preview**, then on the head unit. Check
map rendering, actions, route selection, gestures, Back, native-screen return and
re-entry, and disconnect/reconnect. Complete sign-in and location permissions on
the phone while parked. If it fails, share the connection log and exact app/patch
versions. An app absent from the launcher may not expose a supported service.

## Media libraries

One Media3 browser supports both Media3 library services and legacy Android
media-browser services. It provides folders, Back, 40-item pages, live child
updates, playback by media ID, and the session's available play/pause/skip controls.
Library navigation is retained through Home and map switching until another
audio app is selected or the projected display closes. Releasing a browser does
not pause the media app's playback.

No Google host identity is spoofed. An app can reject browsing or individual
commands. In that case, **Now playing** uses an existing session from the same
package if notification access was granted. Start playback on the phone if no
session is available. The reported Spotify build rejected the browser connection;
neither browsing nor playback-capture access is guaranteed. Media search, artwork,
queue editing, custom actions, and shuffle/repeat are not yet implemented.

See [app compatibility findings](docs/APP_COMPATIBILITY.md) for the reported
Audiobookshelf, Fork Client, Spotify, Home Assistant and Bluetooth-service errors
and what each integration needs.

Test a route → Home → music folders → play → return-to-map workflow, folder/page
Back navigation, denied library access, and disconnects during library loading.

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
    For a wireless adapter, select the adapter here. After choosing a device,
    **Enable wireless auto-connect** associates that device using Android's
    companion-device setup. Future Bluetooth connection events start projection;
    audio authorization may still require opening Veloce's notification.
6. The launcher should appear once the car grants display focus. Try scrolling the
   app grid, Home, directional controls, and **Car** to return to the native screen.
7. If setup fails, use **Share connection log** and include the phone/OS and head unit.

Also test native-screen return and re-entry, phone rotation while previewing,
disconnect during setup, repeated reconnects, and locking the phone. In particular,
private-display visibility under keyguard and screen-off power behavior are still
unverified. Start the first projection test with the phone unlocked.

Active-session fallback controls require notification access; a successfully
connected media library supplies its own controller without that permission.
Playback-capture authorization is separate from media-control access.

Notification contents are not read by Veloce. The listener is used to obtain active
media sessions. It does not bypass an app's library-access or playback-capture policy.

## Verified scope and limitations

- Protocol tests cover framing, real JDK TLS, channel negotiation, fragmented
  video payloads, plaintext/encrypted keepalive, acknowledgement backpressure,
  focus restart, rejected input binding, media audio alongside video, audio codec
  selection/credits/focus, Bluetooth mode, and car/phone-initiated shutdown.
- Android-module tests cover touch-coordinate scaling, resource cleanup, API negotiation,
  AndroidX host binding/lifecycle/callbacks/errors/surfaces, retained navigation,
  and Media3/legacy browsing and cancellation under Robolectric.
  Audio tests also cover mute/notification-setting restoration and adapter Bluetooth
  handoff. They do not validate actual playback capture, AAC encoding, third-party
  map drawing, companion-device presence delivery, or audible car output.
- Projection uses the main H.264 display, supported standard resolutions up to
  1920 × 1080 (or 1080 × 1920), and the offered 30/60 fps configuration. Encoder
  support is device-dependent; automatic renegotiation after codec failure is absent.
- Touch is scaled from the advertised main touchscreen to the visible video area.
  Head-unit margin conventions, D-pad focus, and rotary traversal need car testing.
- Wireless supports an IPv4 car endpoint and open/WPA/WPA2 personal Wi-Fi.
  Mode 8 with a valid passphrase is attempted as WPA2-Personal for the reported
  wireless adapter, with an explicit diagnostic. This is an adapter workaround;
  the public schema labels 8 as WPA2-Enterprise, whose EAP setup is not implemented.
  Bluetooth bootstrap version fields are only partially described publicly;
  broader head-unit interoperability still needs testing.
- **Disconnect** gives protocol goodbye a bounded opportunity to complete, then
  closes the transport. Unexpected link loss also releases capture and local mute.

## Structure

- `app`: phone setup, transports/session service, virtual-display rendering,
  hardware encoding, car launcher, AndroidX template hosting, car-service discovery,
  and active media integration.
- `protocol`: framing, protobuf decoding, TLS, discovery, channel negotiation,
  bounded video flow control, and protocol tests without Android dependencies.
- `veloce-patches`: independent Morphe patch-bundle build and Waze compatibility patch.
- [Foundation brief](FOUNDATION.md): agreed scope, constraints, and primary references.
- [Connection/audio study](docs/CONNECTION_AUDIO_STUDY.md): reference-project findings and platform constraints.

## License

License selection is pending the project owner's decision.
