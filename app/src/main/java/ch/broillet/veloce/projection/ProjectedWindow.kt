package ch.broillet.veloce.projection

import android.app.Presentation
import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ch.broillet.veloce.protocol.Proto
import ch.broillet.veloce.R
import java.io.Closeable

data class ScreenSpec(val width: Int, val height: Int, val density: Int, val cropX: Int = 0, val cropY: Int = 0,
    val touchWidth: Int = width - cropX, val touchHeight: Int = height - cropY) {
    init {
        require(width in 64..4096 && height in 64..2160 && width.toLong() * height <= 8_388_608)
        require(density in 80..640)
        require(cropX in 0 until width && cropY in 0 until height && touchWidth > 0 && touchHeight > 0)
    }
    val label: String get() = "$width × $height · $density dpi"
}

class ProjectedWindow(
    context: Context,
    surface: Surface,
    val spec: ScreenSpec,
    private val back: () -> Unit,
    private val home: () -> Unit,
    private val buttons: CarButtons? = null,
    content: @Composable () -> Unit,
) : Closeable {
    private val display = checkNotNull(context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "Veloce car display", spec.width, spec.height, spec.density, surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )) { "Android could not create the car display" }
    private val presentation: Window
    private var closed = false
    private var downTime = 0L

    init {
        presentation = try { Window(context, display.display) } catch (error: Exception) {
            display.release()
            throw error
        }
        try {
            val view = ComposeView(presentation.context).apply { setContent(content) }
            presentation.content = view
            presentation.setContentView(view)
            presentation.show()
            restoreGeometry()
            presentation.resume()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun restoreGeometry() {
        if (closed) return
        display.resize(spec.width, spec.height, spec.density)
        presentation.window?.apply {
            setLayout(spec.width, spec.height)
            insetsController?.hide(WindowInsets.Type.systemBars())
            decorView.requestLayout()
        }
    }

    fun redraw() {
        if (!closed) presentation.window?.decorView?.invalidate()
    }

    fun touchFromPreview(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val scaled = MotionEvent.obtain(event)
        scaled.transform(Matrix().apply { setScale(spec.width.toFloat() / viewWidth, spec.height.toFloat() / viewHeight) })
        try { presentation.window?.decorView?.dispatchTouchEvent(scaled) } finally { scaled.recycle() }
    }

    fun input(report: Proto) {
        report.bytes(3)?.let { encoded ->
            val touch = Proto(encoded)
            val action = touch.number(3).toInt()
            val points = touch.messages(1)
            val index = touch.number(2).toInt()
            val ids = points.map { it.number(3, -1).toInt() }
            if (action !in listOf(0, 1, 2, 5, 6) || points.size !in 1..10 || index !in points.indices ||
                ids.any { it !in 0..31 } || ids.distinct().size != ids.size) return@let
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN || downTime == 0L) downTime = now
            val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply {
                id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { i -> MotionEvent.PointerCoords().apply {
                x = mapTouch(points[i].number(1), spec.touchWidth, spec.width, spec.cropX)
                y = mapTouch(points[i].number(2), spec.touchHeight, spec.height, spec.cropY)
                pressure = 1f; size = 1f
            } }
            val indexed = if (action == 5 || action == 6) action or (index shl 8) else action
            val event = MotionEvent.obtain(downTime, now, indexed, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { presentation.window?.decorView?.dispatchTouchEvent(event) } finally { event.recycle() }
            if (action == 1) downTime = 0
        }
        report.bytes(4)?.let { encoded ->
            Proto(encoded).messages(1).forEach { key ->
                dispatchKey(key.number(1).toInt(), key.number(2) != 0L, key.number(3).toInt())
            }
        }
        report.bytes(6)?.let { encoded ->
            Proto(encoded).messages(1).forEach { rotation ->
                if (rotation.number(1) != 65536L) return@forEach
                val delta = rotation.number(2).toInt()
                repeat(kotlin.math.abs(delta.toLong()).coerceAtMost(12).toInt()) {
                    val meta = if (delta < 0) KeyEvent.META_SHIFT_ON else 0
                    dispatchKey(KeyEvent.KEYCODE_TAB, true, meta)
                    dispatchKey(KeyEvent.KEYCODE_TAB, false, meta)
                }
            }
        }
    }

    private fun dispatchKey(rawCode: Int, down: Boolean, meta: Int = 0) {
        if (buttons?.key(rawCode, down) == true) return
        val code = if (rawCode == 65541) KeyEvent.KEYCODE_DPAD_CENTER else rawCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) {
            if (!down) if (code == KeyEvent.KEYCODE_BACK) back() else home()
        } else {
            val view = presentation.window?.decorView ?: return
            if (!view.hasFocus()) presentation.content?.requestFocus()
            val now = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, meta))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { presentation.destroy() } finally { display.release() }
    }

    fun cancelInput() {
        buttons?.cancel()
        if (downTime == 0L) return
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { presentation.window?.decorView?.dispatchTouchEvent(event) } finally { event.recycle(); downTime = 0 }
    }

    private class Window(context: Context, display: android.view.Display) : Presentation(context, display, R.style.Theme_Veloce),
        LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        var content: ComposeView? = null

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
            checkNotNull(window).apply {
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                setBackgroundDrawableResource(android.R.color.black)
            }.decorView.apply {
                setViewTreeLifecycleOwner(this@Window)
                setViewTreeSavedStateRegistryOwner(this@Window)
                setViewTreeViewModelStoreOwner(this@Window)
            }
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() {
            content?.disposeComposition()
            registry.currentState = Lifecycle.State.DESTROYED
            dismiss()
            viewModelStore.clear()
        }
    }
}
