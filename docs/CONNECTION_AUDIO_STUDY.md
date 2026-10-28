# Connection, audio, and parked-status study

Date: 2026-10-28.

Implementation follow-up: media forwarding (PCM/AAC over USB/Wi-Fi), basic direct
Bluetooth mode, optional adb setup, companion-device auto-connect, graceful goodbye,
and the Bluetooth-to-Wi-Fi handoff fix are now implemented. Per the owner's updated
direction, parked-only actions are forwarded unconditionally and vehicle-sensor
gating is not being implemented. Waze/native navigation speech and host-generated
guidance are deferred. The sections below preserve the original study findings;
see the main README for current behavior and validation limits.

This report compares the current Veloce implementation with the local
`.source_project` Gearslip snapshot and Android/AOSP contracts. The owner reports
working wired and wireless projection and patched Waze rendering. Native wireless
audio has not been tested. The owner also reports that Gearslip's screen-share
audio worked well, whereas its Bluetooth audio did not.

## Recommendation

Use **audio through the projection connection** as Veloce's default for all three
setups. Keep **direct Bluetooth audio to the car** as an explicit fallback.
Treat media, navigation speech, and telephone calls as distinct audio paths.

| Setup | Screen and forwarded audio | Bluetooth role |
| --- | --- | --- |
| Native wired Android Auto | Phone → USB → head unit | Normally hands-free calls; optional direct Bluetooth media |
| Native wireless Android Auto | Phone → Wi-Fi/TCP → head unit | Wireless bootstrap/control and normally hands-free calls; optional direct Bluetooth media |
| Wired car with wireless adapter | Phone → Wi-Fi/TCP → adapter → USB → head unit | Adapter for bootstrap; car's separate Bluetooth normally for calls and optional direct media |

An adapter does not require a third audio implementation. It presents a wireless
projection endpoint and bridges to the wired car. Its advertised services determine
what Veloce can use. Some adapters proxy those services; others modify them.

**Being paired with the adapter does not mean media is being sent to it.** Bluetooth
pairing, the Android Auto RFCOMM connection, A2DP media, and HFP hands-free calls are
different things. The adapter may offer no media profile at all. Its exact profiles
and behavior need checking on the owner's hardware.

## 1. What Veloce currently does

- `protocol/.../CarSession.kt:117–125` discovers and opens video/input only.
  There is no audio sink negotiation or audio sender, and no sensor subscription.
- `CarSession.kt:142` has a placeholder reply to audio-focus request message 18
  reporting loss; it does not implement coordinated audio focus. Incoming focus
  notifications, message 19, are not handled.
- `app/.../connection/ConnectionService.kt` owns the transport, encoder, and car
  window. It has no playback-capture owner.
- `app/src/main/AndroidManifest.xml` declares a `connectedDevice` foreground
  service, but no playback-capture permission/service setup.
- `app/.../hosting/CarAppHost.kt:245–248` unconditionally rejects parked-only
  delegates. The reported warning is emitted here by Veloce, not by Waze's host
  validation or the Morphe patch.
- `CarAppHost.kt:158–162` receives navigation lifecycle events but discards
  `updateTrip` data. `openMicrophone` is also unimplemented.
- The Waze patch exposes the car service and disables AndroidX host validation.
  It changes neither audio attributes nor audio-capture policy.

Consequently, USB projection without car audio is expected in the current build.
Media browsing, play/pause commands, and notification access do not forward sound.
Wireless projection uses the same session engine, so changing transport alone
cannot add audio.

## 2. How Gearslip handled audio

All Gearslip paths below are relative to
`.source_project/gearslip/src/main/java/app/seb3thehacker/gearslip/`.

### Audio through Gearslip: the owner's working mode

The implemented path was:

```text
Media app playback
    → Android AudioPlaybackCapture / AudioRecord
    → PCM, optionally encoded as AAC
    → Android Auto MEDIA sink
    → USB or Wi-Fi projection transport
    → car speakers
```

`audio/AudioCaptureService.kt:118–203` builds a capture configuration matching
`MEDIA`, `GAME`, and `UNKNOWN` usage, across eligible apps. It captures 16-bit PCM
at the selected sink's rate/channel count, using a dedicated audio-priority thread.
It logs signal levels and reports prolonged all-zero capture as a possible
capture-policy problem rather than immediately blaming the car connection.

`audio/CarAudio.kt` connects that capture to the negotiated sink and retains the
projection grant across media-app changes for the connected session.
`GearslipActivity.kt:261–275` launches the platform permission/consent flow.

This used MediaProjection **without creating a display for that audio capture**.
Android's screen-sharing terminology describes the permission mechanism; it did
not mean the phone screen had to be mirrored to get audio.

`ServiceDiscovery.kt:85–120` selects a MEDIA sink, preferring PCM.
`audio/AudioLink.kt` handles channel opening, codec setup, configuration selection,
stream start/stop, timestamps, acknowledgements, head-unit focus, and AAC encoding
for supported AAC-only sinks. The same sender is used on USB and wireless.

Playback capture copies sound rather than redirecting it. Gearslip therefore mutes
`STREAM_MUSIC` during capture and unmutes it at shutdown
(`AudioCaptureService.kt:40–65`). This is a global media-stream operation, not a
speaker-only or per-app mute. It can affect uncaptured navigation speech or an
existing Bluetooth media route as well. Veloce must preserve prior mute state,
handle failures and route changes, and verify capture levels with the phone muted
on the actual OS/device.

### Bluetooth audio

`GearslipRunner.kt:549–592` leaves the projection MEDIA channel closed when
Bluetooth mode is selected, specifically to avoid head units switching away from
their Bluetooth source. Apps then play using Android's ordinary output routing.

`car/CarBluetooth.kt` offers pairing with the address advertised by the projection
Bluetooth service. It does not select Spotify's or Waze's output device.
`car/BluetoothAudioCheck.kt:96–129` checks connected A2DP devices against that
advertised address, or accepts any A2DP device if no address was supplied.

That check establishes connection, not that the device is the active playback
route or that the head unit is listening to its Bluetooth source. There is no
separate saved adapter identity versus car-audio identity and no reliable global
rerouting of other apps in this mode.

Thus the owner's Bluetooth failure is consistent with a routing/source-selection
problem, but source inspection cannot identify the historical cause. Possibilities
include an inactive A2DP route, calls-only pairing, the car listening to the Android
Auto source, or adapter/head-unit identity mismatch. It does not establish that
Gearslip actually sent Bluetooth media to the adapter.

### Navigation speech: a separate implementation

`car/DirectionsVoice.kt` did **not** capture arbitrary navigation audio. It:

1. Read the next turn from AndroidX trip data, falling back to the navigation
   template's routing information (`car/ClusterNav.kt:198–211`).
2. Constructed its own spoken instruction and announcement timing.
3. Synthesized a WAV file with Android text-to-speech.
4. Converted the audio and sent it through the GUIDANCE sink.
5. Requested transient/ducking car focus around announcements when needed.

`GearslipRunner.kt:594–613` only opens a PCM guidance sink for this feature.

This can deliver useful turn-by-turn directions, but it is not Waze's selected
voice, original timing, or complete hazard/traffic announcement stream. The
owner's successful audio test does not yet establish which navigation voice path
was audible.

### Adapter awareness and compatibility workarounds

Gearslip was not wholly unaware of adapters: its wireless implementation explicitly
handles Wi-Fi information arriving before the endpoint, and repeated information
from ZLink devices (`wireless/WirelessLink.kt:221–245`). Its shared session engine
also naturally permits audio through a wireless-to-wired bridge.

What is missing is an explicit model of the adapter's Bluetooth identity separately
from the car's real Bluetooth audio/call identity.

Gearslip contains several interoperability lessons worth independently reproducing
against public contracts and actual device traces:

- Select the audio **stream type and codec**, not the first audio-looking channel.
  PCM sent to an AAC sink can be acknowledged yet remain inaudible.
- `Setup` identifies the codec; the configuration index belongs in `Start`.
- Negotiate rate, channel count, and sample width rather than assuming 48 kHz stereo.
- Head-unit focus controls access to the speakers independently of video focus.
- Audio needs bounded buffering, acknowledgement handling, and underflow diagnostics.

Some Gearslip recovery heuristics should be treated as hypotheses, not protocol
requirements: assuming readiness after an empty/error setup response or a timer,
starting without focus confirmation, and reopening the acknowledgement window
after a stall. Its acknowledgement handler also does not validate session IDs.
Start with correct state/credit handling; add narrowly justified compatibility
behavior only when a real head-unit trace requires it.

## 3. Android limits that shape the implementation

### Playback capture

The official `AudioPlaybackCaptureConfiguration` contract requires all of:

- Usage `MEDIA`, `GAME`, or `UNKNOWN`.
- An application/player capture policy allowing capture by other apps.
- An eligible manifest setting/target SDK.
- The capturing and playing apps being in the same user profile.

Veloce also needs `RECORD_AUDIO`, MediaProjection authorization, and the appropriate
`mediaProjection` foreground service. This permission name does not mean playback
capture records the physical microphone. Microphone capture is a separate feature.
Managing local media-stream mute also requires the applicable audio-settings
permission and careful restoration of the pre-session state.

Adding `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` to a capture filter does not bypass
the platform's allowed-usage rules. Likewise, a host-validation patch or an adb
consent grant does not override another app's capture policy.

Validate music and Waze separately. For Waze, inspect actual playback attributes
and captured signal using the exact patched build. Public playback callbacks can
show usage information but anonymize app identity; controlled single-app tests or
adb audio diagnostics are needed for reliable attribution.

If Waze's prompts are already eligible, capturing them may work. If not, the
practical paths are:

- Direct Bluetooth playback to the real car, where the head unit supports it
  alongside projection.
- Host-generated directions from AndroidX turn data, with explicit limitations.
- An independently developed app-specific audio compatibility patch/integration
  if preserving Waze's own speech is required. This needs investigation of the
  exact build; it is not solved by the current Morphe patch.

A hybrid of captured music over projection and uncaptured Waze speech over Bluetooth
is not a reliable default: it depends on simultaneous source mixing in the head
unit, and media-stream muting can silence the Bluetooth guidance as well.

Changing an app's navigation output to capturable media, if feasible, also changes
its audio/focus behavior. Separately capturing an eligible navigation app by UID
could feed a guidance sink while excluding it from the media mix. It still would
not supply authoritative announcement boundaries automatically. Avoid promising
native guidance mixing/ducking merely because some speech samples are capturable.

### Routing other apps

`AudioRouting.setPreferredDevice()` applies to the caller's player/recorder, not to
another app's Spotify or Waze player. `AudioManager.setCommunicationDevice()` is
for communications, not global music routing. System-wide strategy routing requires
privileged APIs/permissions. The old `setBluetoothA2dpOn()` is a no-op in the SDK
source inspected.

For Bluetooth fallback, use Android's system output selection/settings and observe
available devices, A2DP/HFP connections, and anticipated routing by audio attributes.
Label these accurately: connected, anticipated route, and confirmed audible output
are different observations. A Veloce-owned test tone can test its own route but
cannot prove another app's route.

### Calls and microphones

Ordinary cellular calls generally continue through the phone/car HFP connection;
they are not captured by MediaProjection. The adapter's call behavior must be
verified separately, since adapters can advertise or bridge hands-free profiles.

The projection microphone service is useful for Veloce/hosted-app voice input but
does not automatically reroute Android telephony. Supporting the car microphone
requires a separate incoming-audio path and AndroidX `openMicrophone` implementation.
Observe advertised in-call availability and test media/guidance interruption and
resume with real calls.

## 4. The two adb commands

Gearslip's setup explicitly offered these two grants. For a future Veloce build
implementing the equivalent functionality, the package-adjusted commands are:

```sh
adb shell appops set ch.broillet.veloce PROJECT_MEDIA allow
adb shell pm grant ch.broillet.veloce android.permission.WRITE_SECURE_SETTINGS
```

The first preauthorizes projection on platform builds honoring that app-op. The
app still obtains a fresh session token through the projection flow; storing and
reusing old consent intents/tokens is not the implementation strategy.

The second grants permission to change a setting; by itself it does not change
notification behavior. Gearslip's `audio/ScreenShareGuard.kt` used it to temporarily
set the global `disable_screen_share_protections_for_apps_and_notifications` value
to 1 during audio capture, then restore it. It recorded ownership to recover after
a crash. This affects screen-sharing protections globally while enabled, rather
than granting per-app notification access.

Veloce currently has neither the audio implementation nor the manifest declaration
and setting-management code for the second command. These are implementation
references, not a present fix for this build. Grants given to Gearslip do not
transfer to Veloce's different package.

Keep these optional conveniences. Normal consent should remain functional, and
behavior must be tested on the owner's Android version/ROM. In particular, do
not equate screen recording's lock behavior with audio-only capture: current AOSP
has stop-policy exemptions for projections without an associated virtual display
and for `PROJECT_MEDIA`. Always handle projection revocation and verify lock,
screen-off, and call-end behavior on-device.

## 5. Fixing parked-only actions

The AndroidX contract delegates parked-only enforcement to the host.
Gearslip's `host/CarModels.kt:231–242` forwarded these clicks without checking.
Although it subscribed to sensors and separately used phone GPS to limit some
notification popups, neither mechanism enforced parked-only actions.

Veloce should discover the car's sensor service, subscribe to relevant advertised
types, and propagate connection-owned status to `CarAppHost`:

| Sensor | Public wire reference | Purpose |
| --- | --- | --- |
| Driving status | SensorBatch field 13 | Head-unit interaction restrictions |
| Parking brake | Field 7, Boolean | Parking evidence |
| Gear | Field 8; park enum value 101 | Parking evidence, where supplied |
| Speed | Field 3 | Contradiction/motion evidence; zero alone does not establish parking |

Driving status is a **restriction bitmask**, not a parked Boolean: 0 is
unrestricted; bits include no video, no keyboard input, no voice input, no
configuration, and limited message length. The public schemas establish wire
values but do not establish Google's full parked-state inference algorithm.

Keep raw restrictions and parking evidence distinct. Derive an action-eligibility
state with three outcomes: allowed by validated vehicle information, restricted,
or unknown. Use reported Park/brake information where available, reconcile
contradictory motion/restriction signals, and validate how head units exposing
only driving status represent parked operation. Do not require every optional
sensor, or interpret a missing protobuf field as the zero/unrestricted value.

Recheck eligibility when dispatching the action, including after queued app calls.
Clear it on session loss/reconnect and relevant sensor errors. Respect whether
updates are periodic or on-change; arbitrary short expiry of a valid unchanged
Park event would incorrectly block a stationary car.

For the adapter setup, inspect whether it forwards the car's real status. Compare
a direct USB session and an adapter session with controlled parking/gear changes.
If it omits or substitutes status, Veloce cannot infer verified parking from the
Bluetooth connection or zero phone GPS speed. Report that specific missing input.
A simulated parked state belongs in preview/testing and must remain separate from
real-session evidence.

## 6. Connection reliability priorities

Veloce already has the right high-level shape: USB and wireless return the same
byte-transport interface, with the connected-device service owning the session.
Extend that ownership to audio and vehicle state.

Important cases from the comparison:

1. **Track different devices.** Remember the chosen wireless bootstrap device
   independently of any car Bluetooth address advertised by discovery. In Bluetooth
   fallback, permit the expected real-car audio device to be identified separately.
   A device name or mismatch alone is not reliable adapter detection.
2. **Keep wireless control and data lifetimes distinct.** Retain RFCOMM and answer
   pings when present; tolerate adapters that end bootstrap after handing off to
   Wi-Fi. Veloce already tolerates read EOF after joining starts, but its network
   worker still requires both Bluetooth status writes to succeed before attempting
   TCP (`WirelessConnector.kt:120–127`). A close during Wi-Fi association can
   therefore still abort an otherwise viable handoff. Test this timing explicitly.
3. **Preserve internet access.** Bind the projection socket to the local car network,
   rather than binding all app traffic. Veloce already does this. Verify navigation
   and streaming over mobile data, including the owner's VPN configuration if used.
4. **Handle repeats and restarts.** Duplicate bootstrap messages must be idempotent;
   changed credentials/endpoints or adapter reboot require an explicit new attempt.
   Veloce's one-shot `joining` flag does not implement mid-session rebootstrap.
5. **Clean teardown and bounded reconnect.** Test cable bounce, ignition cycles,
   Wi-Fi loss, screen lock, and quick reconnect. Release capture, stream focus,
   queues, network requests, and mute ownership; reject old-session callbacks and
   acknowledgements. Implement phone-initiated protocol goodbye where practical.
6. **Automatic wireless startup later in this foundation pass.** Gearslip uses
   CompanionDeviceManager association/presence. Build equivalent behavior against
   current platform APIs for the saved bootstrap device after manual sessions are
   reliable. Companion association itself does not provide audio-capture consent.

## 7. Suggested implementation order

### A. Discovery diagnostics and parked status

Log the offered audio codecs/configurations, relevant sensor types and decoded
status transitions, and distinct Bluetooth roles. Implement the sensor channel and
host action gate. This addresses the visible Waze problem and produces the evidence
needed for adapter compatibility.

### B. Projection media audio on all transports

Add a transport-independent audio sink implementation to `protocol` and a
session-owned Android playback-capture component to `app`.

- Start with a known-good source/test tone to prove the negotiated car sink,
  separately from third-party capture eligibility.
- Prefer supported PCM configurations; implement AAC-LC/ADTS fallback for head
  units that require it. Never silently send PCM to an AAC sink.
- Use per-stream session IDs and acknowledgement windows, monotonic/sample-based
  timing, bounded latency, and explicit stop/restart handling.
- Give control and latency-sensitive audio timely access to the shared writer.
  Video congestion must not consume all buffering or starve keepalive/focus.
- Coordinate car audio focus with media playback without taking Android focus
  from the very media app being captured. Do not tie audio lifetime to whichever
  car page currently has video focus.
- Restore local audio state on denial, capture revocation, sink failure, and
  disconnect. A silent capture is not sufficient evidence to select a different
  Bluetooth destination automatically.

### C. Navigation speech and interruption handling

Measure Waze's native guidance first. If it is not capturable, provide clearly
identified host-generated guidance from Trip/RoutingInfo as the general fallback,
then investigate a targeted integration if native Waze voices/alerts are required.
Add the GUIDANCE sink and proper transient/ducking focus. Validate prompts over
music and over the car radio, duplicate-voice prevention, rerouting cancellation,
calls, and restoration of the previous source.

### D. Bluetooth fallback and automatic reconnect

Add accurate route diagnostics and a system-output/settings entry for direct car
Bluetooth. Leave the projection MEDIA sink inactive in this mode. Then complete
saved-device auto-connect and recovery from adapter/ignition cycles.

The basic UI can be a single audio-mode choice, connection/audio/status text, and
the existing diagnostic export. Negotiated capabilities should handle the three
connection configurations without a separate elaborate setup flow for each.

## 8. Validation needed on the actual devices

For each of the three setups, exercise:

- Media alone; Waze alone; media plus Waze; phone locked and screen off.
- Car volume changes, pause/resume, switching apps, returning to the car radio.
- Incoming/outgoing calls and navigation/music recovery afterwards.
- Both adapter and car Bluetooth connected; verify actual A2DP and HFP roles.
- Consent accepted/denied/revoked, optional adb grants, and prior phone mute state.
- Parked/restricted transitions, missing sensor fields, reconnect after state change.
- Repeated connect/disconnect and a sustained session for gaps/latency/underflows.

Protocol regressions should cover codec/config selection, stale/zero acknowledgements,
focus transitions, cancellation during capture/negotiation, and absent versus zero
sensor values. Device tests must separately establish audible output and eligible
capture; acknowledgements alone prove neither.

Useful owner details for that pass: phone model/Android version, car/head-unit model,
adapter model/firmware, exact patched Waze build, and whether the previously working
Gearslip voice was Waze's selected voice or Gearslip's generated directions.

## References and confidence

The implementation findings above are direct source observations. The owner's
successful hardware tests are user-reported. The proposed routes are supported by
the platform/protocol contracts and bridge architecture, but the exact adapter's
profiles, audio sink acceptance, sensor forwarding, and Waze capture eligibility
remain device-validation questions.

Official sources inspected:

- [AOSP AudioPlaybackCaptureConfiguration](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/AudioPlaybackCaptureConfiguration.java): allowed usages, capture policies, profiles, loopback plus rendering.
- [AOSP AudioRouting](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/AudioRouting.java): per-player routing.
- Installed official API 37.0 SDK sources: `AudioManager`, `AudioRecord`, `AudioPlaybackConfiguration`, `MediaProjectionManager`, `MediaProjection`, and `Settings`.
- [AOSP projection permission activity](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/SystemUI/src/com/android/systemui/mediaprojection/permission/MediaProjectionPermissionActivity.java).
- [AOSP projection stop controller](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/media/projection/MediaProjectionStopController.java): lock/stop exemptions.
- [AOSP notification protection controller](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/SystemUI/src/com/android/systemui/statusbar/policy/SensitiveNotificationProtectionControllerImpl.java): global-setting and projection-sensitive notification behavior.
- [AndroidX ParkedOnlyOnClickListener](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/model/ParkedOnlyOnClickListener.java).
- [AndroidX NavigationManager](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/navigation/NavigationManager.java) and [Step](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/navigation/model/Step.java).

The Android developer guide endpoints were unavailable during this review, so the
Android findings were checked against official SDK/AOSP/AndroidX source contracts.

Public reverse-engineered wire references, distinct from official Android API documentation:

- [aasdk media contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/media): MediaSinkService, AudioConfiguration, AudioStreamType, Setup, Config, Ack.
- [aasdk control contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/control/message): audio-focus requests/states.
- [aasdk sensor contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/sensorsource): subscription IDs, SensorBatch, driving restrictions, gear, brake, speed.
- [aa-proxy-rs architecture](https://github.com/aa-proxy/aa-proxy-rs#how-it-works-technical): an independent wireless-to-USB bridge example, including the separate car Bluetooth connection. This establishes a concrete bridge design, not the behavior of every commercial adapter.
