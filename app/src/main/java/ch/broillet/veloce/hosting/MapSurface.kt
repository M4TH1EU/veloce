package ch.broillet.veloce.hosting

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun MapSurface(host: CarAppHost, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        object : TextureView(context), TextureView.SurfaceTextureListener {
            private var target: SurfaceContainer? = null
            private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val x = detector.focusX
                    val y = detector.focusY
                    val factor = detector.scaleFactor
                    host.gesture { it.onScale(x, y, factor) }
                    return true
                }
            })
            private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    val x = event.x
                    val y = event.y
                    host.gesture { it.onClick(x, y) }
                    return true
                }
                override fun onScroll(first: MotionEvent?, last: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (!scale.isInProgress && last.pointerCount == 1) host.gesture { it.onScroll(distanceX, distanceY) }
                    return true
                }
                override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (!scale.isInProgress) host.gesture { it.onFling(velocityX, velocityY) }
                    return true
                }
            })
            init {
                surfaceTextureListener = this
                setOnTouchListener { view, event ->
                    scale.onTouchEvent(event)
                    gestures.onTouchEvent(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
            }
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                publish(texture, width, height)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                release()
                publish(texture, width, height)
            }
            private fun publish(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(width, height)
                host.surfaceArea(Rect(0, 0, width, height))
                target = SurfaceContainer(Surface(texture), width, height, resources.displayMetrics.densityDpi).also(host::surfaceAvailable)
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
            fun release() { target?.let(host::surfaceDestroyed); target = null }
        }
    }, onRelease = { it.release() })
}
