package ch.broillet.veloce.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import ch.broillet.veloce.R

@Composable
internal fun CarTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    symbol: CarSymbol? = null, selected: Boolean = false, enabled: Boolean = true) {
    GlassSurface(modifier.heightIn(min = 64.dp), onClick = onClick, selected = selected, enabled = enabled,
        shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            symbol?.let { CarGlyph(it, Modifier.size(28.dp), MaterialTheme.colorScheme.primary) }
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun CarSearchField(value: String, onValueChange: (String) -> Unit, hint: String,
    search: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier, RoundedCornerShape(24.dp), opacity = .94f) {
        TextField(value, onValueChange, Modifier.fillMaxWidth().heightIn(min = 64.dp), singleLine = true,
            placeholder = { Text(hint, maxLines = 1) }, textStyle = MaterialTheme.typography.titleMedium,
            leadingIcon = { CarGlyph(CarSymbol.Search, Modifier.size(28.dp), MaterialTheme.colorScheme.primary) },
            shape = RoundedCornerShape(24.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    }
}

@Composable
internal fun HostNotice(message: String, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier, RoundedCornerShape(24.dp), opacity = .98f) {
        Row(Modifier.height(64.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CarGlyph(CarSymbol.Warning, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            GlassIconButton(CarSymbol.Close, stringResource(R.string.dismiss), dismiss, size = 56.dp, iconSize = 24.dp)
        }
    }
}
