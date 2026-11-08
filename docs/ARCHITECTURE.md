# Architecture

Veloce is independently implemented in Kotlin using Compose, coroutines, and
StateFlow. Android APIs and AndroidX define the platform/hosting contracts; public
reverse-engineered references describe the projection wire protocol.

## Ownership

- `ConnectionService` owns one connection attempt, transport, `CarSession`, video
  encoder, projected window, optional playback capture, and a session-scoped partial
  wake lock. It retains the location foreground-service type while audio is started
  or stopped. `ConnectionSetupActivity` obtains per-drive permissions and a fresh
  audio token without requiring `MainActivity` to be open.
- USB accessory and wireless Bluetooth/Wi-Fi bootstrap produce the same `CarLink`
  byte-stream interface. Only the projection socket binds to the car's Wi-Fi network,
  leaving other app traffic on Android's normal network.
- `CarSession` owns authentication, discovery, channels, focus, and shutdown. Separate
  bounded write queues prioritize control/audio over video. Stream-specific
  acknowledgement windows provide backpressure and reject stale credits. Phone-side
  pings keep idle links alive; a withheld display focus request is retried once with
  the legacy display-channel field. Media buttons remain usable without video focus;
  screen-directed input is ignored in that state.
- `ProjectedWindow` renders app-owned UI on a private virtual display. Its surface
  feeds H.264 encoding for the car or a TextureView for the phone preview.
- Each display's `CarWorkspace` retains one AndroidX host and one media browser.
  Hidden navigation stays bound with its UI stopped; replacing it or closing the
  display ends the session. Hosted apps own their location and guidance behavior;
  bindings include the host's foreground capabilities, including navigation location.
- Playback capture belongs to the connection, not the visible app. Cleanup restores
  acquired media mute and optional screen-share settings, with process-restart recovery.

## Boundaries

No Google host identity is spoofed. Car TLS identity and APK signing identity are
separate: the former authenticates projection, the latter identifies Veloce to apps.
The public TLS identity is downloaded from a pinned aasdk revision, not bundled.
Notification contents stay in memory; connection diagnostics are bounded and local.

Wireless supports IPv4 and open/WPA/WPA2-compatible personal networks.
Video uses standard main-display H.264 modes up to 1920 × 1080 or 1080 × 1920;
encoder failure does not trigger automatic mode renegotiation.

## References

- [USB accessories](https://developer.android.com/develop/connectivity/usb/accessory)
- [Car App Library](https://developer.android.com/training/cars/apps)
- [Map surfaces](https://developer.android.com/training/cars/apps/library/draw-maps)
- [Car-app lifecycles](https://developer.android.com/training/cars/apps/library/lifecycles)
- [Media3 browser/controller](https://developer.android.com/media/media3/session/connect-to-media-app)
- [Playback capture](https://developer.android.com/media/platform/av-capture)
- [Foreground-service types and location prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Service binding capabilities](https://developer.android.com/reference/android/content/Context#BIND_INCLUDE_CAPABILITIES)
- [Public projection schemas](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf)
- [Wire framing and session details](../protocol/README.md)
