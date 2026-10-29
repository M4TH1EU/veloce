# Audio

## Projection audio (default)

**Audio through Veloce** captures eligible playback and forwards it to the negotiated
media sink, preferring 16-bit PCM with AAC-LC/ADTS fallback. USB, native wireless,
and wireless-to-USB adapters use the same path. The adapter's Bluetooth identity
is for startup, not necessarily for media or hands-free calls.

Android requires recording permission and screen-sharing consent. Veloce uses the
grant for playback audio only; it does not mirror the phone screen. If startup was
in the background, open Veloce's notification to enable audio. Authorization lasts
for the connected session; **Stop audio** releases it and **Enable audio** requests
a fresh grant. Local media is muted during capture and restored afterward.

Capture only includes eligible MEDIA/GAME/UNKNOWN playback in the same user profile.
Apps can prohibit capture, producing silence. Notification access and host patches
do not override this policy. Native navigation-guidance audio and cellular calls
are outside this path; Veloce does not synthesize replacement guidance.

## Direct Bluetooth

Disconnect, disable **Audio through Veloce**, and select the real car in Android's
Bluetooth/output settings. The projection media sink stays closed. Veloce reports
Android's anticipated route but cannot force another app's player onto a device.
Keep the car's hands-free pairing separate from the adapter's startup pairing.

## Optional adb setup

Normal Android consent works without these grants:

```sh
adb shell appops set ch.broillet.veloce PROJECT_MEDIA allow
adb shell pm grant ch.broillet.veloce android.permission.WRITE_SECURE_SETTINGS
```

The first preauthorizes sharing on Android builds that honor this app-op. The second
lets Veloce temporarily disable global screen-share notification protection during
capture and restore its previous value afterward. This affects system-wide sharing
protections, not just Veloce. Neither grant bypasses an audio app's capture policy.
