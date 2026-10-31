package ch.broillet.veloce.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.media.MediaArtwork
import ch.broillet.veloce.media.MediaAction
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private val artworkCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

internal fun artworkCacheKey(artwork: MediaArtwork?): String? = artwork?.uri?.toString()
    ?: artwork?.data?.let { "data:${it.size}:${it.contentHashCode()}" }
    ?: artwork?.bitmap?.let { artwork.cacheKey ?: "bitmap:${it.generationId}" }

@Composable
internal fun rememberArtwork(artwork: MediaArtwork?): Bitmap? {
    val context = LocalContext.current
    val key = remember(artwork) { artworkCacheKey(artwork) }
    val initial = remember(key) { key?.let(artworkCache::get) ?: artwork?.bitmap }
    val bitmap by produceState(initial, key) {
        if (key == null || artwork == null) { value = null; return@produceState }
        val cached = artworkCache.get(key) ?: artwork.bitmap
        if (cached != null) {
            artworkCache.put(key, cached)
            value = cached
            return@produceState
        }
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
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { decoded ->
                    artworkCache.put(key, decoded)
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
internal fun Artwork(artwork: MediaArtwork?, modifier: Modifier) {
    ArtworkImage(rememberArtwork(artwork), modifier)
}

@Composable
internal fun ArtworkImage(bitmap: Bitmap?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
            CarGlyph(CarSymbol.Music, Modifier.fillMaxSize(.45f), MaterialTheme.colorScheme.primary.copy(alpha = .65f))
        }
    }
}

@Composable
internal fun ArtworkTransition(bitmap: Bitmap?, modifier: Modifier, content: @Composable (Bitmap?, Modifier) -> Unit) {
    var current by remember { mutableStateOf(bitmap) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    var transitioning by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(bitmap) {
        if (current === bitmap) return@LaunchedEffect
        previous = current
        current = bitmap
        transitioning = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(450))
        previous = null
        transitioning = false
    }
    Box(modifier) {
        val surface = MaterialTheme.colorScheme.surface
        if (transitioning) Box(Modifier.fillMaxSize().background(surface)) { content(previous, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(surface)) { content(current, Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun AlbumBackdrop(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds()) {
        ArtworkTransition(bitmap, Modifier.fillMaxSize()) { art, layer ->
            if (art != null) {
                Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = layer.graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                        .blur(56.dp, BlurredEdgeTreatment.Unbounded))
            } else Box(layer.cabinBackground())
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .52f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f)))))
    }
}

@Composable
internal fun MediaActionIcon(action: MediaAction, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, action.iconPackage, action.iconResource, action.iconUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = action.iconUri?.let { runCatching { Icon.createWithContentUri(it).loadDrawable(context) }.getOrNull() }
                    ?: action.iconPackage?.takeIf { action.iconResource != 0 }?.let {
                        Icon.createWithResource(it, action.iconResource).loadDrawable(context)
                    }
                drawable?.toBitmap(80, 80)
            }.getOrNull()
        }
    }
    val art = bitmap
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (action.enabled) 1f else .3f)
    if (art != null) Image(art.asImageBitmap(), null, modifier, colorFilter = ColorFilter.tint(tint))
    else CarGlyph(CarSymbol.Action, modifier, tint)
}
