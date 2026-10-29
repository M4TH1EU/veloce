# Projection protocol

Android-independent framing, TLS, discovery, video/audio flow control, input, and
shutdown. USB and wireless transports both implement `CarLink`.

## Framing

Fields are in network byte order:

| Field | Bytes |
| --- | --- |
| Channel | 1 |
| Flags | 1 |
| Payload length | 2 |
| Total plaintext message size (FIRST fragment only) | 4 |
| Payload | declared length |

Flag bits 0–1 identify continuation/first/last/complete; bit 2 marks channel control,
bit 3 encryption. Upper bits are rejected. `FrameDecoder` accepts arbitrary chunks;
USB callers must feed whole bulk-transfer reads. `MessageAssembler` bounds aggregate
fragment reservations. Discard both on connection loss or malformed input.

## Sessions

`TlsEngine` drives a serialized TLS 1.2 `SSLEngine` in server mode; the car is the
client. Authentication precedes encrypted discovery/channel traffic. Control pings
may remain plaintext and receive replies with matching encryption.

`CarSession` uses bounded queues, prioritizing control/audio over video. Codec
configuration consumes acknowledgement credit. Video focus restarts allocate a new
stream ID, resend configuration, and wait for a keyframe; stale credits are ignored.
`AudioSink` independently negotiates media format/focus and stream-scoped credit.
Direct Bluetooth mode leaves that sink unopened. Android capture/encoding stay in
`app`. Disconnect attempts a bounded goodbye exchange before closing the transport.

`Proto` reads the supported schema fields using protobuf-javalite coded streams.
Unknown fixed-width fields are skipped; packed and unpacked integer lists are accepted.

Wire references: [framing](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/include/aasdk/Messenger),
[public schemas](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf).
