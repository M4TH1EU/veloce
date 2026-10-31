package ch.broillet.veloce.hosting

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

internal class MapScene {
    var visibleInsets by mutableStateOf(PaddingValues(0.dp))
    var stableInsets by mutableStateOf(PaddingValues(0.dp))
    var panStart: () -> Unit = {}
    var panEnd: () -> Unit = {}
}
