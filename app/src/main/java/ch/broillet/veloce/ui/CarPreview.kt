package ch.broillet.veloce.ui

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.projection.CarButtons
import ch.broillet.veloce.projection.ProjectedWindow
import ch.broillet.veloce.projection.ScreenSpec

@Composable
fun CarPreview(model: HomeModel, modifier: Modifier, close: () -> Unit) {
    var width by rememberSaveable { mutableIntStateOf(800) }
    var height by rememberSaveable { mutableIntStateOf(480) }
    var density by rememberSaveable { mutableIntStateOf(160) }
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val spec = ScreenSpec(width, height, density)
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.car_preview), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = close) { Text(stringResource(R.string.close_preview)) }
        }
        Box {
            FilledTonalButton(onClick = { menu = true }) { Text(spec.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(ScreenSpec(800, 480, 160), ScreenSpec(1280, 720, 240), ScreenSpec(1920, 1080, 320),
                    ScreenSpec(1280, 480, 160), ScreenSpec(1920, 720, 240)).forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.label) }, onClick = {
                        width = preset.width; height = preset.height; density = preset.density; menu = false
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.custom_resolution)) }, onClick = { menu = false; custom = true })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            key(spec) { PreviewSurface(model, spec, Modifier.aspectRatio(width.toFloat() / height), close) }
        }
    }
    if (custom) ResolutionDialog(spec, { custom = false }) {
        width = it.width; height = it.height; density = it.density; custom = false
    }
}

@Composable
private fun PreviewSurface(model: HomeModel, spec: ScreenSpec, modifier: Modifier, close: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val navigation = remember { CarNavigation() }
    val app = LocalContext.current.applicationContext as VeloceApplication
    val buttons = remember(navigation) { CarButtons({ navigation.mediaKey?.invoke(it) }, { app.connections.log("Assistant button received (not implemented)") }) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                object : TextureView(context), TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    private var projected: ProjectedWindow? = null
                    init {
                        surfaceTextureListener = this
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                            projected?.touchFromPreview(event, view.width, view.height)
                            true
                        }
                    }
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        error = null
                        try {
                            texture.setDefaultBufferSize(spec.width, spec.height)
                            val target = Surface(texture).also { surface = it }
                            projected = ProjectedWindow(context, target, spec, { navigation.back(close) }, navigation::home, buttons) {
                                val catalog by model.state.collectAsStateWithLifecycle()
                                VeloceTheme { CarLauncher(spec, catalog.apps, navigation, model.media, close) }
                            }
                        } catch (failure: Exception) {
                            release()
                            error = failure.message ?: failure.javaClass.simpleName
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(spec.width, spec.height)
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
                    fun release() { projected?.close(); projected = null; surface?.release(); surface = null }
                }
            },
            onRelease = { it.release() },
        )
        error?.let { Text(stringResource(R.string.preview_error, it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ResolutionDialog(spec: ScreenSpec, dismiss: () -> Unit, apply: (ScreenSpec) -> Unit) {
    var width by rememberSaveable { mutableStateOf(spec.width.toString()) }
    var height by rememberSaveable { mutableStateOf(spec.height.toString()) }
    var density by rememberSaveable { mutableStateOf(spec.density.toString()) }
    val valid = runCatching { ScreenSpec(width.toInt(), height.toInt(), density.toInt()) }.getOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.custom_resolution)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text(stringResource(R.string.width_pixels)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.height_pixels)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(density, { density = it }, label = { Text(stringResource(R.string.density_dpi)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (valid == null) Text(stringResource(R.string.invalid_resolution), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid != null, onClick = { valid?.let(apply) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
