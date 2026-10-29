# App compatibility

Veloce discovers exported AndroidX `CarAppService`, Media3 `MediaLibraryService`,
and legacy `MediaBrowserService` endpoints. Discovery does not imply access: apps
can restrict callers by package/signature or require account setup.

## Car apps

An app must expose an AndroidX car service and accept Veloce as its host.
Host allowlists use package `ch.broillet.veloce` and the **APK signing certificate's**
SHA-256 digest, not the projection TLS certificate. A host-validation patch does
not turn a proprietary car interface into an AndroidX one or add missing host APIs.

- **Waze / Google Maps:** require builds exposing a compatible AndroidX service and
  accepting custom hosts. Patches are maintained separately from this project.
- **Home Assistant:** release builds use a host allowlist; accepting Veloce does not
  supply the car hardware APIs its features may need.

Complete sign-in and location permissions on the phone while parked. Veloce supports
a template subset, not arbitrary phone activities or full Car App API conformance.

## Media apps

- **Audiobookshelf:** upstream restricts browsers through `VALID_MEDIA_BROWSERS`.
- **Forkgram / Telegram:** upstream `MusicBrowserService` restricts browser callers
  and exposes chat audio, not the messaging interface.
- **Spotify:** browsing depends on the installed build's access policy.
- OEM Bluetooth browser services may appear without a browsable remote source;
  they are not Veloce's audio-output selector.

If browsing is refused, grant notification access and start playback on the phone
to use **Now playing**. Controls depend on an active media session. Neither this
fallback nor a host patch overrides an app's [playback-capture policy](AUDIO.md).

## App-side access policies

- [AndroidX HostValidator](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/validation/HostValidator.java)
- [Audiobookshelf](https://github.com/advplyr/audiobookshelf-app/blob/master/android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt)
- [Forkgram](https://github.com/Forkgram/TelegramAndroid/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/MusicBrowserService.java)
- [Home Assistant](https://github.com/home-assistant/android/blob/main/app/src/main/kotlin/io/homeassistant/companion/android/vehicle/HaCarAppService.kt)
