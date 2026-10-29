package ch.broillet.veloce.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.R
import ch.broillet.veloce.notifications.CarNotifications
import kotlinx.coroutines.delay

@Composable
fun CarNotificationsContent(notifications: CarNotifications, modifier: Modifier = Modifier) {
    val notices by notifications.notices.collectAsStateWithLifecycle()
    val connected by notifications.connected.collectAsStateWithLifecycle()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!connected) item { Text(stringResource(R.string.notification_access_needed)) }
        else if (notices.isEmpty()) item { Text(stringResource(R.string.no_notifications)) }
        items(notices, key = { it.key }) { notice ->
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(notice.app, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { notifications.remove(notice.key) }) { Text(stringResource(R.string.hide_notification)) }
                    }
                    if (notice.title.isNotBlank()) Text(notice.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (notice.text.isNotBlank()) Text(notice.text, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
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
    Card(onClick = { notifications.dismissAlert(current.sequence); open() }, modifier = modifier.widthIn(max = 600.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(current.notice.app, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.notice.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.notice.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { notifications.dismissAlert(current.sequence) }) { Text(stringResource(R.string.hide_notification)) }
        }
    }
}
