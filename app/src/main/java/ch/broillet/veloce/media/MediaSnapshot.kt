package ch.broillet.veloce.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri

internal fun artworkIdentity(packageName: String, album: String?, artist: String?, mediaId: String?, title: String?): String =
    if (!album.isNullOrBlank()) "$packageName:album:$album:$artist" else "$packageName:track:${mediaId ?: title}:$artist"

internal fun platformNowPlaying(controller: MediaController): NowPlaying {
    val metadata = controller.metadata
    val state = controller.playbackState
    val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
    val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
    val id = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
    return NowPlaying(controller.packageName, title, artist, state?.state == PlaybackState.STATE_PLAYING, state?.actions ?: 0,
        metadata?.let { data -> MediaArtwork(
            uri = (data.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: data.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: data.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))?.let(Uri::parse),
            bitmap = data.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: data.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: data.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            cacheKey = artworkIdentity(controller.packageName, album, data.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: artist, id, title)) },
        state?.customActions.orEmpty().take(12).map { MediaAction(it.action, it.name.toString(), it.icon, controller.packageName) },
        speed = state?.playbackSpeed?.takeIf { it > 0 } ?: 1f,
        positionMs = state?.position ?: -1, durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
        updatedAtMs = state?.lastPositionUpdateTime ?: 0, mediaId = id, album = album)
}
