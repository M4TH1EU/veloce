package ch.broillet.veloce.hosting

import androidx.car.app.model.Toggle
import androidx.compose.material3.Switch
import androidx.compose.runtime.*

@Composable
internal fun HostedToggle(toggle: Toggle, host: CarAppHost) {
    var checked by remember(toggle.isChecked) { mutableStateOf(toggle.isChecked) }
    Switch(checked, { value ->
        checked = value
        host.event { toggle.onCheckedChangeDelegate.sendCheckedChange(value, it) }
    }, enabled = toggle.isEnabled)
}
