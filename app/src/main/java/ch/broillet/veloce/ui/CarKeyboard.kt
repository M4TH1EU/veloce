package ch.broillet.veloce.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R

internal data class CarKeyboardRequest(val text: String, val hint: String, val change: (String) -> Unit, val submit: (String) -> Unit)

internal val LocalCarKeyboard = staticCompositionLocalOf<(CarKeyboardRequest) -> Unit> { {} }

@Composable
internal fun CarKeyboardHost(navigation: CarNavigation, content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<CarKeyboardRequest?>(null) }
    SideEffect { navigation.dismissKeyboard = if (request != null) ({ request = null }) else null }
    DisposableEffect(navigation) { onDispose { navigation.dismissKeyboard = null } }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalCarKeyboard provides { request = it }, content = content)
        request?.let { input ->
            CarOverlay({ request = null }) {
                key(input) { CarKeyboard(input, { request = null }) }
            }
        }
    }
}

@Composable
internal fun CarOverlay(dismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .72f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
        content()
    }
}

@Composable
private fun CarKeyboard(request: CarKeyboardRequest, close: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(request.text, TextRange(request.text.length))) }
    var upper by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun replace(text: String) {
        val start = value.selection.min
        val end = value.selection.max
        val updated = value.text.replaceRange(start, end, text).take(200)
        value = TextFieldValue(updated, TextRange((start + text.length).coerceAtMost(updated.length)))
        request.change(updated)
    }
    fun erase() {
        if (value.selection.collapsed && value.selection.start > 0) {
            val end = value.selection.start
            value = value.copy(selection = TextRange(value.text.offsetByCodePoints(end, -1), end))
        }
        replace("")
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.padding(12.dp).widthIn(max = 900.dp).fillMaxWidth().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> { erase(); true }
            android.view.KeyEvent.KEYCODE_ENTER -> { request.submit(value.text); close(); true }
            else -> event.nativeKeyEvent.unicodeChar.takeIf { it > 0 && !Character.isISOControl(it) }?.let {
                replace(String(Character.toChars(it))); true
            } ?: false
        }
    }.focusRequester(focus).focusable(), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val keyHeight = ((maxHeight - 164.dp) / 4).coerceIn(40.dp, 60.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value, { value = it }, Modifier.weight(1f), readOnly = true, singleLine = true,
                        placeholder = { Text(request.hint) }, textStyle = MaterialTheme.typography.titleLarge)
                    GlassIconButton(CarSymbol.Close, stringResource(R.string.close_keyboard), close, size = 56.dp)
                }
                val rows = if (symbols) listOf("1234567890", "@#€%&*()-", "!\"':;/?.,")
                    else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
                rows.forEachIndexed { index, letters ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (index == 1) 20.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index == 2 && !symbols) KeyboardKey("⇧", { upper = !upper }, Modifier.weight(1.3f).height(keyHeight), upper,
                            stringResource(R.string.keyboard_shift))
                        letters.forEach { letter ->
                            val text = if (upper) letter.uppercase() else letter.toString()
                            KeyboardKey(text, { replace(text) }, Modifier.weight(1f).height(keyHeight))
                        }
                        if (index == 2) KeyboardKey("⌫", ::erase, Modifier.weight(1.3f).height(keyHeight),
                            label = stringResource(R.string.keyboard_delete))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardKey(if (symbols) "ABC" else "123", { symbols = !symbols }, Modifier.weight(1.3f).height(keyHeight))
                    KeyboardKey(stringResource(R.string.keyboard_space), { replace(" ") }, Modifier.weight(4f).height(keyHeight))
                    KeyboardKey(stringResource(R.string.search), { request.submit(value.text); close() },
                        Modifier.weight(2f).height(keyHeight), selected = true)
                }
            }
        }
    }
}

@Composable
private fun KeyboardKey(text: String, click: () -> Unit, modifier: Modifier, selected: Boolean = false, label: String = text) {
    GlassSurface(modifier.semantics { contentDescription = label }, RoundedCornerShape(14.dp), onClick = click, selected = selected, opacity = .98f) {
        Text(text, Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
