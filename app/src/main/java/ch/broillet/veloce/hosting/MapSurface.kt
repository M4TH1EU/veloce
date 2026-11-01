package ch.broillet.veloce.hosting

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import androidx.car.app.SurfaceContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal class MapScene {
    var visibleInsets by mutableStateOf(PaddingValues(0.dp))
    var stableInsets by mutableStateOf(PaddingValues(0.dp))
    var panStart: () -> Unit = {}
    var panEnd: () -> Unit = {}
}

@Composable
internal fun MapSurface(host: CarAppHost, modifier: Modifier, visibleInsets: PaddingValues = PaddingValues(0.dp),
    stableInsets: PaddingValues = visibleInsets, onPanStart: () -> Unit = {}, onPanEnd: () -> Unit = {},
    blurred: Boolean = false, interactive: Boolean = true) {
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    fun PaddingValues.pixels() = with(density) {
        Rect(calculateLeftPadding(direction).roundToPx(), calculateTopPadding().roundToPx(),
            calculateRightPadding(direction).roundToPx(), calculateBottomPadding().roundToPx())
    }
    val insets = visibleInsets.pixels()
    val stable = stableInsets.pixels()
    val beginPan by rememberUpdatedState(onPanStart)
    val endPan by rememberUpdatedState(onPanEnd)
    val blurRadius = with(density) { 28.dp.toPx() }
    AndroidView(modifier = modifier, factory = { context ->
        object : TextureView(context), TextureView.SurfaceTextureListener {
            private var target: SurfaceContainer? = null
            private val contentInsets = Rect()
            private val stableContentInsets = Rect()
            private var dragging = false
            private var blurred = false
            private var interactive = true
            fun presentation(blur: Boolean, touch: Boolean) {
                this.interactive = touch
                if (this.blurred != blur) {
                    this.blurred = blur
                    setRenderEffect(if (blur) RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP) else null)
                }
            }
            fun updateInsets(insets: Rect, stable: Rect) {
                if (contentInsets == insets && stableContentInsets == stable) return
                contentInsets.set(insets)
                stableContentInsets.set(stable)
                publishArea(width, height)
            }
            private fun publishArea(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                fun area(insets: Rect): Rect {
                    val left = insets.left.coerceIn(0, width - 1)
                    val top = insets.top.coerceIn(0, height - 1)
                    return Rect(left, top, (width - insets.right).coerceIn(left + 1, width),
                        (height - insets.bottom).coerceIn(top + 1, height))
                }
                host.surfaceArea(area(contentInsets), area(stableContentInsets))
            }
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
                    if (!scale.isInProgress && last.pointerCount == 1) {
                        if (!dragging) { dragging = true; beginPan() }
                        host.gesture { it.onScroll(distanceX, distanceY) }
                    }
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
                    if (!this.interactive) return@setOnTouchListener false
                    scale.onTouchEvent(event)
                    gestures.onTouchEvent(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    if (dragging && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                        dragging = false
                        endPan()
                    }
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
                publishArea(width, height)
                target = SurfaceContainer(Surface(texture), width, height, resources.displayMetrics.densityDpi).also(host::surfaceAvailable)
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
            fun release() { target?.let(host::surfaceDestroyed); target = null }
        }
    }, update = { it.updateInsets(insets, stable); it.presentation(blurred, interactive) }, onRelease = { it.release() })
}
