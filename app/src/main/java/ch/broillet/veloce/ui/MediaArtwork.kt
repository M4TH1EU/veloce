package ch.broillet.veloce.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import ch.broillet.veloce.media.MediaArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
internal fun Artwork(artwork: MediaArtwork?, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(artwork?.bitmap, artwork) {
        value = artwork?.bitmap
        if (value != null || artwork == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val limit = 2 * 1024 * 1024
                val bytes = artwork.data ?: artwork.uri?.let { uri ->
                    when (uri.scheme) {
                        "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { it.readNBytes(limit + 1) }
                        "https", "http" -> (URI(uri.toString()).toURL().openConnection() as HttpURLConnection).run {
                            connectTimeout = 5_000
                            readTimeout = 5_000
                            try { inputStream.use { it.readNBytes(limit + 1) } } finally { disconnect() }
                        }
                        else -> null
                    }
                } ?: return@runCatching null
                if (bytes.size > limit) return@runCatching null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) }
}
