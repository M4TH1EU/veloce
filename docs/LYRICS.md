# Lyrics

Veloce looks lyrics up when the full-screen lyrics view is opened. The title,
artist, album and recording duration identify the recording. Playback, seeking
and word highlighting use the media session's monotonic position timestamps.

Providers are queried independently; an early plain or line-synced result can
be replaced by a word-synced result. Existing cached lyrics remain usable when
a provider is unavailable. Sources are displayed in the lyrics view.

- [Better Lyrics](https://better-lyrics.boidu.dev/): TTML word/syllable timings.
  Uses its current [documented API](https://docs.betterlyrics.org/). Public cache
  misses that require an API key fall through to the other providers.
- [LRCLIB](https://lrclib.net/): exact recording lookup followed by a metadata-
  and duration-checked search. Supports plain text, LRC and word-timed
  Lyricsfile YAML. [API reference](https://lrclib.net/docs.md).
- KuGou: public song/lyrics search and its returned candidate download keys;
  line-synced LRC is decoded locally.

TTML parsing follows the provider's documented media-time convention and
preserves spaces between timed syllables. Background/translation spans are
omitted. LRC offsets, repeated line timestamps and enhanced word timestamps
are supported. XML external declarations and YAML aliases are rejected.

The private `cache/lyrics-v1` directory stores successful results atomically,
including word timings and attribution. It is bounded to 128 documents / 8 MiB,
with a 64-record memory cache. Results are checked again after 24 hours. Misses
last five minutes in memory only; Retry bypasses that miss cache. Network
timeouts, response-size limits and per-host `Retry-After` backoff apply.
