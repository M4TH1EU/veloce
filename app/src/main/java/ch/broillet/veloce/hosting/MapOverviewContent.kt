package ch.broillet.veloce.hosting

import android.text.format.DateFormat
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.TravelEstimate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.ui.CarSymbol
import ch.broillet.veloce.ui.GlassIconButton
import ch.broillet.veloce.ui.GlassSurface
import ch.broillet.veloce.ui.HostNotice
import java.util.Date
import java.util.SimpleTimeZone

@Composable
internal fun MapOverviewContent(template: NavigationTemplate, host: CarAppHost, modifier: Modifier,
    insets: PaddingValues, mapScene: MapScene?) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val travel = template.destinationTravelEstimate
    val showFooter = travel != null || host.navigating
    var footerHeight by remember { mutableStateOf(80.dp) }
    val area = PaddingValues(
        start = insets.calculateStartPadding(direction),
        top = insets.calculateTopPadding() + if (host.notice != null) 76.dp else 0.dp,
        end = insets.calculateEndPadding(direction),
        bottom = insets.calculateBottomPadding() + if (showFooter) footerHeight + 12.dp else 0.dp,
    )
    Box(modifier) {
        if (mapScene == null) MapSurface(host, Modifier.fillMaxSize(), area, interactive = false)
        else SideEffect {
            mapScene.visibleInsets = area
            mapScene.stableInsets = area
            mapScene.panStart = {}
            mapScene.panEnd = {}
        }
        Box(Modifier.fillMaxSize().padding(insets)) {
            host.notice?.let { HostNotice(it, host::dismissNotice, Modifier.align(Alignment.TopCenter).fillMaxWidth()) }
            if (showFooter) MapTripSummary(travel, host.navigating, host::stopNavigation,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } })
        }
    }
}

@Composable
private fun MapTripSummary(estimate: TravelEstimate?, navigating: Boolean, stop: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val arrival = estimate?.arrivalTimeAtDestination?.let {
        DateFormat.getTimeFormat(context).apply {
            timeZone = SimpleTimeZone(it.zoneOffsetSeconds * 1_000, it.zoneShortName.orEmpty())
        }.format(Date(it.timeSinceEpochMillis))
    }
    val remaining = estimate?.remainingTimeSeconds?.takeIf { it >= 0 }?.let { stringResource(R.string.remaining_minutes, (it + 59) / 60) }
    val detail = listOfNotNull(remaining?.takeIf { arrival != null }, estimate?.remainingDistance.distance().takeIf { it.isNotBlank() }).joinToString(" · ")
    GlassSurface(modifier, RoundedCornerShape(20.dp), opacity = .96f) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(arrival?.let { stringResource(R.string.arrival_time, it) } ?: remaining ?: stringResource(R.string.navigation),
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (navigating) GlassIconButton(CarSymbol.Close, stringResource(R.string.stop_navigation), stop, size = 56.dp, opacity = .12f)
        }
    }
}
