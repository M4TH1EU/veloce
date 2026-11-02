package ch.broillet.veloce.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaColorsTest {
    @Test fun tinyVibrantDetailsDoNotTintNeutralArtwork() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (x in 0..4) for (y in 0..4) bitmap.setPixel(x, y, Color.RED)
        val accent = artworkAccent(bitmap)!!
        assertTrue(kotlin.math.abs(accent.red - accent.green) < .04f)
        assertTrue(kotlin.math.abs(accent.green - accent.blue) < .04f)
        bitmap.recycle()
    }

    @Test fun representativeColorsStillProduceAReadableAccent() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 80, 180))
        val accent = artworkAccent(bitmap)!!
        assertTrue(accent.blue > accent.red + .1f)
        assertTrue(accent.blue > accent.green)
        assertTrue(minOf(accent.red, accent.green, accent.blue) > .6f)
        bitmap.recycle()
    }
}
