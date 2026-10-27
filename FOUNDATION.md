# Foundation brief

Status: implementation reviewed and desktop checks passing; actual Android/car
connection and projection remain unvalidated.

## Confirmed direction

- Independently implemented Android phone-side projection client.
- No Google Play services or root required at runtime.
- First milestone includes USB and wireless connections.
- A minimal car launcher discovers and opens compatible Android Auto apps.
- Prioritize reliability, readable code, and a coherent interface.
- Name: Veloce. Application ID: `ch.broillet.veloce`.
- Minimum Android 14 (API 34); compile/target Android 17 (API 37).
- Both Bluetooth audio and audio forwarded over the projection link are in scope.
- Wireless starts manually in the first milestone.
- Reference apps: Spotify, patched Waze, and patched Google Maps.
- The project owner will choose the license later.

## Implemented foundation

- Independent Gradle project with `app` and `protocol` modules.
- Phone UI is setup/status only; the launcher is rendered on the car display.
- Discovery of exported AndroidX car, media-browser, and Media3 library services.
- Optional notification-listener access for active media-session metadata and controls.
- Incremental projection frame encoder/decoder, checked against public wire-layout
  references, with bounded allocations and transport-boundary/truncation tests.
- TLS 1.2 server authentication with a checksum-pinned, downloaded public aasdk identity.
- USB accessory transport and Bluetooth/Wi-Fi/TCP wireless bootstrap.
- Session-owned foreground service, bounded write queue, and video acknowledgements.
- Negotiated private virtual display, surface-input H.264 encoder, and car input dispatch.
- Shared car UI in a selectable resolution/DPI preview using a TextureView-backed display.
- Car-initiated shutdown acknowledgement, native-focus handling, and local connection logs.

Twenty-three protocol tests include a simulated head unit completing real JDK TLS,
channel negotiation, fragmented video packet delivery, keepalive, input, and shutdown.
Two app-module JVM tests cover coordinate scaling and resource ownership. Physical
Android rendering, encoder behavior, Bluetooth/Wi-Fi setup, and car acceptance still
need hardware validation. App hosting and forwarded audio remain unimplemented.

## Implementation review

- Keepalive accepts plaintext control-channel pings, as used by the public aasdk
  reference, and replies with matching encryption. Other post-authentication messages
  require encryption. TLS access is synchronized and repeated/trailing handshake
  data is rejected.
- Video configuration and frame packets share the negotiated acknowledgement window.
  Native focus stops the stream and cancels blocked producers; re-entry starts a new
  session ID, resends codec configuration, and waits for a keyframe. Old-session and
  zero-count acknowledgements cannot grant credit to the new stream.
- The encoder releases output buffers before waiting on transport credit. Its drain
  thread owns final codec/surface release after startup; shutdown interrupts credit
  waits without racing a timed join against codec destruction. No first encoded frame
  within ten seconds is reported as a failure.
- Cancellation during encoder preparation retains a local owner until the main-thread
  handoff. Presentation construction failures release the virtual display. Car windows
  explicitly request hardware acceleration before installing Compose content and use
  a full-screen theme; composition disposal accompanies display teardown.
- Preview reapplies the selected buffer dimensions after TextureView size changes.
- Input selects the main display's service, binds only implemented advertised keys,
  scales advertised touchscreen dimensions into the uncropped video rectangle, and
  maps the primary controller button to Select. Rotary input uses sequential focus
  traversal. Input is suppressed for projected-without-input focus.
- Wireless waits for IPv4 link properties, keeps Wi-Fi/TCP ownership independent of
  bootstrap read EOF once network setup has begun, and stops retrying after cancellation.
  Unsupported Wi-Fi security modes fail explicitly rather than being treated as WPA2.

These are source-contract checks and desktop regressions, not device observations.
The simulator uses this project's framing/TLS helpers and synthetic H.264 bytes;
it is not an independent protocol conformance suite or a video decoder.

## Proposed first milestone

1. Phone setup: connection status, required permissions, certificate/key import,
   disconnect action, and locally exportable diagnostic logs.
2. USB accessory transport with attach, permission, detach, and reconnect handling.
3. Wireless transport with paired-device selection, Bluetooth bootstrap,
   local Wi-Fi connection, and a network-bound TCP socket.
4. Shared session engine: framing, TLS, service negotiation, channel lifecycle,
   keepalive, focus, and orderly shutdown.
5. Surface-based H.264 projection with negotiated dimensions, bounded buffering,
   acknowledgements, and keyframe recovery.
6. Launcher with app icons, home/back, touch, and rotary/D-pad focus navigation.
7. Car App Library hosting: lifecycle/API negotiation, template rendering,
   app actions, map surfaces, surface gestures, and day/night configuration.
8. Media app integration: service discovery, browsing, playback controls,
   metadata, and a minimal now-playing view.

The initial template set and acceptance workflows must follow selected test apps.
Advertise only capabilities actually implemented. Discovery alone does not establish
that an app can be hosted.

## Architecture

- Kotlin, coroutines, StateFlow, and Compose.
- One Android application module and one Android-independent protocol module.
- A foreground connected-device service owns each active car session.
- USB and wireless provide interchangeable byte transports to the session engine.
- UI, encoding, app hosting, and media integration have explicit lifetimes owned by
  the session; no process-global mutable callback registry.
- Use AndroidX models and maintained platform libraries where appropriate.
- Use protobuf-javalite for wire decoding rather than a bespoke protobuf parser.
- Test fragmented/malformed protocol input, state transitions, cancellation, and
  reconnection. Validate rendering, locked-phone behavior, and both transports on hardware.

## Findings that affect implementation

- USB accessory APIs expose the connection, not the Android Auto wire protocol.
  Public reverse-engineered protocol references are also necessary.
- Current AndroidX host validation requires signature allowlisting on phones.
  App compatibility depends on app/library versions and host identity.
- Navigation apps render their maps into a host-provided Surface; the host renders
  template chrome and reports visible/stable map areas.
- Ordinary activities cannot simply be treated as car-compatible applications.
- Private virtual displays can render application-owned UI without mirroring the
  phone screen. Other-app playback capture is a separate consent-gated mechanism.
- Car TLS identity and APK signing identity serve different purposes. Certificate
  acceptance must be tested with the intended head unit.
- Test bounded video queues, static-screen refresh, scaling/cropping, Bluetooth
  channel lifetime, and map location/network access while the phone is locked.

## Pending validation

- License and contribution terms, selected by the project owner.
- Actual phone/OS and car/head-unit details. The owner's cars support both transports.
- Exact navigation patch/build versions and the host interfaces they expose. A host
  validation patch alone does not establish AndroidX Car App Library compatibility.
- Spotify session controls, browsing restrictions, and playback-capture policy.
- Certificate acceptance on each head unit.
- Verify USB/wireless startup, negotiated screen margins, touch alignment, and native-focus return.
- Verify car display survival with the phone locked and repeat connect/disconnect cycles.
- Private displays remain subject to Android keyguard/power policy. No privileged
  display flags or screen-lock bypass are used; locked-phone projection is not promised.
- Verify Android's TLS provider and the downloaded public head-unit identity in the
  phone/server role. JDK TLS tests use an ephemeral test identity instead.
- Video negotiation currently chooses a supported standard mode near 720p, without
  retrying a smaller mode if the device's encoder rejects it. Version response is
  fixed at 1.7; secondary displays and runtime mode changes are unsupported.
- Test a one-packet acknowledgement window, native/projected focus cycling, static
  frames, crop/touch alignment, rotary selection, and preview resize/rotation.
- Wireless currently supports IPv4 and open/WPA2-compatible personal networks. Public
  bootstrap version fields have incomplete semantics; matching a schema is not proof
  of interoperability. Phone-side Disconnect closes the link without a goodbye exchange.

## Audio constraints

Forwarded audio uses the projection protocol over either USB or wireless; it is not
USB Audio Class output. Capturing another app's playback requires Android's
MediaProjection consent and audio permission, plus the correct foreground service.
Notification access provides media controls, not audio capture or browse permission.

The platform only captures eligible MEDIA, GAME, or UNKNOWN playback whose app/player
allows capture. Navigation-guidance usage is excluded. Test the actual map apps'
audio attributes before deciding how to deliver spoken guidance over projection.

## Primary references

- [Android USB accessory guide](https://developer.android.com/develop/connectivity/usb/accessory)
- [Android car app guide](https://developer.android.com/training/cars/apps)
- [Navigation app guide](https://developer.android.com/training/cars/apps/navigation)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [AndroidX HostValidator source](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/validation/HostValidator.java)
- [AndroidX CarAppService source](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/CarAppService.java)
- [AndroidX SurfaceCallback source](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/SurfaceCallback.java)
- [AOSP DisplayManager source](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/hardware/display/DisplayManager.java)
- [AOSP UsbAccessory source](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/hardware/usb/UsbAccessory.java)
- [AOSP MediaBrowser source](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/browse/MediaBrowser.java)
- [Public projection protocol reference](https://github.com/opencardev/aasdk)
- [AOSP playback capture contract](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/AudioPlaybackCaptureConfiguration.java)
- [Public control protocol contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/control)
- [Public video protocol contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/media)
- [Public wireless protocol contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/aaw)

Additional contracts checked during this review:

- [Java SSLEngine contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLEngine.html): buffer/status handling and record ordering.
- [AOSP Presentation](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/app/Presentation.java) and [WindowManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/WindowManager.java): display context, window theme, hardware acceleration before content attachment.
- [AOSP TextureView](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/TextureView.java): buffer-size changes in `onSizeChanged`.
- [AOSP ConnectivityManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/net/ConnectivityManager.java): availability versus link-property callbacks.
- [Public control-channel framing](https://github.com/opencardev/aasdk/blob/9bf6adf933665dee26532201719fac14a047ccf1/src/Channel/Control/ControlServiceChannel.cpp): plaintext ping versus encrypted shutdown.
- [Public input contract](https://github.com/opencardev/aasdk/blob/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/inputsource/InputSourceService.proto): display ID, touchscreen dimensions, supported keys.
- [Public media acknowledgement contract](https://github.com/opencardev/aasdk/blob/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/media/source/message/Ack.proto): session ID and acknowledgement count.

Android API findings were checked against the installed official API 37 SDK sources.
Reference-project source has not been imported. Protocol reference selection and
dependency/license review remain separate from the owner's license decision.
Default identity files are downloaded at runtime from the pinned public reference,
with SHA-256 checksums, and are not bundled in the application or repository.
