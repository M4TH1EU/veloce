package ch.broillet.veloce.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import ch.broillet.veloce.R

@Composable
internal fun CarTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    symbol: CarSymbol? = null, selected: Boolean = false, enabled: Boolean = true) {
    GlassSurface(modifier.heightIn(min = 68.dp), onClick = onClick, selected = selected, enabled = enabled,
        shape = RoundedCornerShape(34.dp)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
            symbol?.let { CarGlyph(it, Modifier.size(28.dp), ink) }
            Text(label, style = MaterialTheme.typography.titleMedium, color = ink)
        }
    }
}

@Composable
internal fun CarSearchField(value: String, onValueChange: (String) -> Unit, hint: String,
    search: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalCarKeyboard.current
    val change by rememberUpdatedState(onValueChange)
    val submit by rememberUpdatedState(search)
    GlassSurface(modifier, RoundedCornerShape(40.dp), opacity = .98f,
        onClick = { keyboard(CarKeyboardRequest(value, hint, { change(it) }, { submit(it) })) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarGlyph(CarSymbol.Search, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank { hint }, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun HostNotice(message: String, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier, RoundedCornerShape(24.dp), opacity = .98f) {
        Row(Modifier.height(68.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CarGlyph(CarSymbol.Warning, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            GlassIconButton(CarSymbol.Close, stringResource(R.string.dismiss), dismiss, size = 60.dp, iconSize = 28.dp, opacity = 0f)
        }
    }
}

@Composable
internal fun CarRefreshButton(refresh: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val label = stringResource(R.string.refresh)
    GlassIconButton(CarSymbol.Refresh, label, refresh, modifier, enabled = enabled)
}
