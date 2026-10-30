# Signing Veloce

The Home Assistant and Spotify compatibility patches ask for the **SHA-256 of
Veloce's APK signing certificate**. This is public information. Do not give the
patcher your keystore, private key, or password. The car/projection TLS identity
and the SHA-256 of an APK file are unrelated.

## Test with your existing debug build

No new key is required for local testing:

```sh
./gradlew :app:assembleDebug
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Copy **Signer #1 certificate SHA-256 digest** into the `hostCertificate` option
when patching Home Assistant full or Spotify in Morphe. Install that same Veloce
APK. Audiobookshelf's package-only allowlist patch does not require a digest.

Debug signing keys differ between computers. Repatch the apps if you change
Veloce's signing key. Do not use a debug key for a release.

## Create a stable release key

Run these yourself; `keytool` prompts for passwords and certificate details:

```sh
mkdir -p "$HOME/.local/share/veloce"
keytool -genkeypair -keystore "$HOME/.local/share/veloce/release.jks" -storetype JKS -alias veloce -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.local/share/veloce/release.jks"
```

Keep an encrypted offline backup of the keystore and its passwords. Losing the
key prevents normal updates to installed builds signed with it.

Put these properties in **`~/.gradle/gradle.properties`**, not in this repository.
Replace the path with your actual absolute path; Gradle does not expand `~` here:

```properties
veloce.storeFile=/absolute/path/to/.local/share/veloce/release.jks
veloce.storePassword=YOUR_STORE_PASSWORD
veloce.keyAlias=veloce
veloce.keyPassword=YOUR_KEY_PASSWORD
```

Restrict access to that file too (`chmod 600 ~/.gradle/gradle.properties`). Then:

```sh
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Without the signing properties, release builds remain unsigned. With them,
Gradle signs releases automatically. Debug builds continue to use their debug
key. Use the **release APK's certificate digest** for patches used with release
Veloce; certificate digests from debug and release are not interchangeable.

Changing between debug and release keys normally requires uninstalling Veloce
first, which removes its data and permission grants. Back up what you need.
Patched third-party APKs also use a different signing key from their originals:
back up their settings/downloads before replacement, and retain Morphe's signing
key for subsequent patched updates.
