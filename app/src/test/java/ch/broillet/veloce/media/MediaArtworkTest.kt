package ch.broillet.veloce.media

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import ch.broillet.veloce.ui.artworkCacheKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaArtworkTest {
    @Test fun clonedSessionBitmapsDoNotBecomeNewCoversOnPauseOrSeek() {
        val first = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val clone = first.copy(Bitmap.Config.ARGB_8888, false)
        val album = artworkIdentity("music", "Album", "Artist", "track-1", "Song one")
        val nextTrack = artworkIdentity("music", "Album", "Artist", "track-2", "Song two")
        assertEquals(album, nextTrack)
        assertEquals(artworkCacheKey(MediaArtwork(bitmap = first, cacheKey = album)), artworkCacheKey(MediaArtwork(bitmap = clone, cacheKey = album)))
        assertNotEquals(artworkCacheKey(MediaArtwork(bitmap = first, cacheKey = album)),
            artworkCacheKey(MediaArtwork(bitmap = clone, cacheKey = artworkIdentity("music", "Other album", "Artist", "track-3", "Song three"))))
        val uri = Uri.parse("content://covers/album")
        assertEquals(artworkCacheKey(MediaArtwork(uri, bitmap = first)), artworkCacheKey(MediaArtwork(uri, bitmap = clone)))
    }
}
