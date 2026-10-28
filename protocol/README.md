# Projection framing

`Frame` and `FrameDecoder` describe the shared byte framing beneath the USB and
wireless projection sessions. Each connection owns its own decoder.

Wire layout, in network byte order:

| Field | Size |
| --- | --- |
| Channel | 1 byte |
| Flags | 1 byte |
| Payload length | 2 bytes |
| Total message size, FIRST fragment only | 4 bytes |
| Payload | Payload length |

Flags: bits 0–1 indicate continuation/first/last/complete, bit 2 is channel control,
and bit 3 is encryption. Unsupported upper bits are rejected.

`feed` accepts arbitrary chunks and emits complete frames. Callers must pass the
whole received USB bulk transfer, not read the accessory descriptor a header at a
time. Header-sized reads can discard the rest of a transfer on Android.

The decoder retains at most one 65,535-byte payload plus its header. Declared message
sizes above the configured limit are rejected. `MessageAssembler` also enforces an
aggregate reservation limit across channels. Each connection discards its decoder
and assembler on close or malformed input.

`TlsEngine` drives a standard TLS 1.2 SSLEngine in server mode. It consumes the car's
encapsulated handshake records and encrypts/decrypts individual projection frames.
The car is the TLS client. No Android or Google Play services APIs are involved.
TLS calls are serialized; repeated handshakes, trailing handshake data, incomplete
encrypted application records, and renegotiation are rejected. Tests exercise the
JDK provider, not Android's TLS provider or the downloaded production identity.

`CarSession` handles version/authentication, service discovery, dynamically assigned
channel IDs, H.264 configuration/focus, media audio, input binding, keepalive, and
shutdown. A bounded writer runs separately from the reader so writes cannot block
receipt of acknowledgements. Control/audio traffic takes priority over the bounded
video queue. An acknowledgement window bounds in-flight video and releases
blocked producers when focus is lost or the session closes. Codec configuration
also consumes credit. Each focus restart sends Stop/Start with a new media session
ID, rejects old-session acknowledgements, and sends configuration before a keyframe.
Projected-without-input focus renders video but suppresses input dispatch. Plaintext fragments
are limited to 12 KiB before TLS wrapping, below a 16 KiB USB accessory transfer.

Control-channel PingRequest/PingResponse may be plaintext even after authentication
(the public aasdk control implementation uses this). Replies match the request's
encryption. Other post-authentication messages must be encrypted. Phone-side forced
close releases the transport; phone-initiated disconnect attempts goodbye with a
bounded wait, and car-initiated goodbye is acknowledged.

`AudioOffer` selects a supported MEDIA sink, preferring PCM to AAC-LC/ADTS, with
16-bit mono/stereo input and a supported sample rate. `AudioSink` negotiates codec
and configuration, requests media focus, and sends timestamped data with per-stream
acknowledgement credit. Stop/restart invalidates old packets and acknowledgements.
Audio failure is reported independently of video. Bluetooth mode leaves the media
channel unopened. Android capture and AAC encoding belong to the app module.

`Proto` uses the maintained protobuf-javalite coded streams to read/write the small
set of supported fields. Unknown fixed-width fields are skipped; packed and unpacked
configuration/key lists are both accepted.

Tests generate an ephemeral local certificate using the JDK's keytool. The simulated
head unit exercises real TLS and protocol packets, including a fragmented 40 KB video
payload and a single-packet credit window. It checks stale/zero acknowledgements,
keyframe recovery after focus changes, plaintext keepalive, rejected input binding,
and plaintext-shutdown rejection. It does not decode H.264 pixels or emulate Android's
display/permission APIs, and shares framing helpers with the implementation.

Public wire-layout references consulted:

- [Frame flags](https://github.com/opencardev/aasdk/blob/newdev/include/aasdk/Messenger/FrameType.hpp)
- [Header fields](https://github.com/opencardev/aasdk/blob/newdev/include/aasdk/Messenger/FrameHeader.hpp)
- [Length fields](https://github.com/opencardev/aasdk/blob/newdev/src/Messenger/FrameSize.cpp)
- [Encryption bit](https://github.com/opencardev/aasdk/blob/newdev/include/aasdk/Messenger/EncryptionType.hpp)
- [Control bit](https://github.com/opencardev/aasdk/blob/newdev/include/aasdk/Messenger/MessageType.hpp)
- [Media contracts](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/media)
- [Audio focus and goodbye](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/control/message)

The Kotlin implementation and tests are independently written. These references
establish the wire format; they are not dependencies of this module.
