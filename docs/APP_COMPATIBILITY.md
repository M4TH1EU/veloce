# App compatibility findings

Based on the reported 28 October 2026 device log and the upstream sources below.
An exported service makes an app discoverable; it does not mean the app accepts
Veloce as a browser/host. The current launcher discovers services rather than
maintaining a tested-app allowlist.

## Waze

The log reaches `library=1.9.0-alpha02, API=7`, completes the handshake and app
creation, then fails at manager retrieval. AndroidX `Bundleable` deserializes
manager responses into `IAppManager` / `INavigationManager`. Remote AIDL proxies
implement `IInterface`, not `IBinder`; the previous host cast was wrong. Veloce
now consumes the typed interfaces directly. Host tests now force remote proxies
and exercise both template retrieval and navigation commands.

The installed Waze build already accepted Veloce's identity. Try the updated
Veloce APK with that Waze installation first. For a fresh patched installation,
see [Veloce Patches](../veloce-patches/README.md). The bundle permits custom hosts
and exposes Waze's existing AndroidX service. Actual map rendering/navigation in
the user's Waze build still needs device verification.

## Audiobookshelf

The upstream `PlayerNotificationService` explicitly checks `VALID_MEDIA_BROWSERS`
and returns `null` from `onGetRoot` for other packages. Its list does not include
`ch.broillet.veloce`. This explains the reported session rejection.

Browsing needs an Audiobookshelf change/patch adding Veloce to that list (or an
upstream custom-host setting). Server sign-in and a working library are also
needed after access is granted. Notification access does not change this list.

## Fork Client / Telegram

Forkgram's inherited `MusicBrowserService.onGetRoot` accepts the system, itself,
Google's media simulator and Android Auto; other callers receive `null`.
Browsing needs an app-side allowlist change/patch accepting Veloce. This service
browses audio from chats, not Telegram's messaging interface. The precise installed
Fork build was not supplied; this finding is from Forkgram's upstream source.

## Spotify

The log establishes that the media service rejects the browser connection, but
does not reveal why. Spotify is proprietary, and these logs do not prove which
package/signature, account or integration check rejected it.

Full browsing needs Spotify to accept Veloce, via a supported integration or a
version-specific app patch after inspecting that build's connection policy.
Opening/signing into Spotify and testing active playback can establish whether
the existing **Now playing** fallback works; it does not grant browsing access.

## Home Assistant

`Unknown host 'ch.broillet.veloce'` is an explicit AndroidX host-validation
rejection. Upstream `HaCarAppService` uses `R.array.hosts_allowlist` for release
builds and `ALLOW_ALL_HOSTS_VALIDATOR` for debuggable builds.

It needs a host allowlist entry for Veloce's package and APK signing-certificate
SHA-256 digest, an app-side validation patch, or a development build configured
to accept custom hosts. The APK certificate is unrelated to Veloce's car TLS
certificate. After validation, its use of car hardware APIs and its actual
templates must also be checked against Veloce's current host subset.

## “Audio Bluetooth”

The label alone cannot identify the package. It is likely an OEM/system Bluetooth
media-browsing service, such as an AVRCP controller for audio on another Bluetooth
device, rather than a music library installed by the user. Such services can be
discoverable without having a browsable remote source connected.

Code `-1` is Media3's generic unknown error, not proof of a permission or sign-in
failure. Identify the package/service before deciding whether it needs a connected
Bluetooth source, an OEM-specific integration, or should be omitted from the
launcher. It is not Veloce's audio-output selector and does not enable projection
audio. A Waze host patch cannot change its behavior.

## Existing media fallback

Grant Veloce notification access, start playback in the chosen audio app on the
phone, then use **Now playing**. Controls depend on the app publishing an active
session and the actions it exposes. This is separate from browsing. Projection
audio forwarding remains unimplemented; sound routing is through the phone's
available outputs, including ordinary Bluetooth audio when connected.

## References

- [AndroidX CarAppBinder: manager responses](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/CarAppBinder.java)
- [AndroidX Bundler: IInterface versus IBinder](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/serialization/Bundler.java)
- [Audiobookshelf PlayerNotificationService](https://github.com/advplyr/audiobookshelf-app/blob/master/android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt)
- [Forkgram MusicBrowserService](https://github.com/Forkgram/TelegramAndroid/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/MusicBrowserService.java)
- [Home Assistant HaCarAppService](https://github.com/home-assistant/android/blob/main/app/src/main/kotlin/io/homeassistant/companion/android/vehicle/HaCarAppService.kt)
- [AndroidX HostValidator](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/validation/HostValidator.java)
- [Media3 SessionError](https://developer.android.com/reference/androidx/media3/session/SessionError)
