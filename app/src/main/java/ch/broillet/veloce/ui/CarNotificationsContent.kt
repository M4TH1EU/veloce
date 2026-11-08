package ch.broillet.veloce.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.R
import ch.broillet.veloce.notifications.CarNotifications
import ch.broillet.veloce.notifications.CarNotice
import kotlinx.coroutines.delay
import java.util.Date

@Composable
fun CarNotificationsContent(notifications: CarNotifications, modifier: Modifier = Modifier) {
    val notices by notifications.notices.collectAsStateWithLifecycle()
    val connected by notifications.connected.collectAsStateWithLifecycle()
    if (!connected || notices.isEmpty()) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GlassSurface(Modifier.size(96.dp), RoundedCornerShape(32.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
                CarGlyph(CarSymbol.Bell, Modifier.size(44.dp).align(Alignment.Center), MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(if (connected) R.string.all_caught_up else R.string.notifications), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(if (connected) R.string.notifications_empty_detail else R.string.notification_access_needed),
                Modifier.widthIn(max = 480.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(notices, key = { it.key }) { notice ->
            CarNotificationCard(notice) { notifications.remove(notice.key) }
        }
    }
}

@Composable
private fun CarNotificationCard(notice: CarNotice, hide: () -> Unit) {
    val is24Hour = LocalAppSettings.current.clockFormat.is24Hour(LocalContext.current)
    val time = android.text.format.DateFormat.format(if (is24Hour) "HH:mm" else "h:mm a", Date(notice.postedAt)).toString()
    GlassSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PackageIcon(notice.packageName, Modifier.size(36.dp))
                Text(notice.app, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassIconButton(CarSymbol.Close, stringResource(R.string.hide_notification), hide, size = 48.dp, iconSize = 24.dp, opacity = 0f)
            }
            if (notice.title.isNotBlank()) Text(notice.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (notice.text.isNotBlank()) Text(notice.text, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CarNotificationBanner(notifications: CarNotifications, open: () -> Unit, modifier: Modifier = Modifier) {
    val alert by notifications.alert.collectAsStateWithLifecycle()
    val current = alert ?: return
    val remaining = 7_000 - (SystemClock.elapsedRealtime() - current.createdAt)
    LaunchedEffect(current.sequence) {
        if (remaining > 0) delay(remaining)
        notifications.dismissAlert(current.sequence)
    }
    if (remaining <= 0) return
    GlassSurface(onClick = { notifications.dismissAlert(current.sequence); open() },
        modifier = modifier.widthIn(max = 600.dp).fillMaxWidth(), selected = true, opacity = 1f) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PackageIcon(current.notice.packageName, Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(current.notice.app, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary)
                Text(current.notice.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.notice.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            GlassIconButton(CarSymbol.Close, stringResource(R.string.hide_notification), { notifications.dismissAlert(current.sequence) },
                size = 48.dp, iconSize = 24.dp, opacity = 0f)
        }
    }
}
