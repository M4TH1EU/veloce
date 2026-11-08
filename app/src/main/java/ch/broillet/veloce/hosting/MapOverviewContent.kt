package ch.broillet.veloce.hosting

import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.MessageInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.ui.CarSymbol
import ch.broillet.veloce.ui.GlassIconButton
import ch.broillet.veloce.ui.GlassSurface
import ch.broillet.veloce.ui.HostNotice
import ch.broillet.veloce.ui.LocalAppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.SimpleTimeZone

@Composable
internal fun MapOverviewContent(template: NavigationTemplate?, host: CarAppHost, modifier: Modifier,
    insets: PaddingValues, mapScene: MapScene?) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val travel = template?.destinationTravelEstimate
    val showFooter = travel != null || host.navigating
    val showHeader = host.notice != null || template?.navigationInfo != null
    var headerHeight by remember { mutableStateOf(0.dp) }
    var footerHeight by remember { mutableStateOf(68.dp) }
    val area = PaddingValues(
        start = insets.calculateStartPadding(direction),
        top = insets.calculateTopPadding() + if (showHeader) headerHeight + 12.dp else 0.dp,
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
            if (showHeader) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                host.notice?.let { HostNotice(it, host::dismissNotice, Modifier.fillMaxWidth()) }
                template?.let { CompactDirections(it, host) }
            }
            if (showFooter) MapTripSummary(travel, host.navigating, host::stopNavigation,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } })
        }
    }
}

@Composable
private fun CompactDirections(template: NavigationTemplate, host: CarAppHost) {
    val info = template.navigationInfo ?: return
    GlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), opacity = .98f) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (info) {
                is RoutingInfo -> {
                    if (info.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarImage(info.currentStep?.maneuver?.icon, host, Modifier.size(44.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            info.currentDistance?.let { Text(it.distance(), style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                            val cue = info.currentStep?.cue.text().ifBlank { info.currentStep?.road.text() }
                            if (cue.isNotBlank()) Text(cue, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                is MessageInfo -> {
                    Text(info.title.text(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.text.text().isNotBlank()) Text(info.text.text(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MapTripSummary(estimate: TravelEstimate?, navigating: Boolean, stop: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val is24Hour = LocalAppSettings.current.clockFormat.is24Hour(context)
    val locale = LocalConfiguration.current.locales[0]
    val arrival = estimate?.arrivalTimeAtDestination?.let {
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", locale).apply {
            timeZone = SimpleTimeZone(it.zoneOffsetSeconds * 1_000, it.zoneShortName.orEmpty())
        }.format(Date(it.timeSinceEpochMillis))
    }
    val remaining = estimate?.remainingTimeSeconds?.takeIf { it >= 0 }?.let { stringResource(R.string.remaining_minutes, (it + 59) / 60) }
    val summary = listOfNotNull(remaining, estimate?.remainingDistance.distance().takeIf { it.isNotBlank() }, arrival).joinToString(" · ")
    GlassSurface(modifier, RoundedCornerShape(20.dp), opacity = .96f) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary.ifBlank { stringResource(R.string.navigation) }, Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (navigating) GlassIconButton(CarSymbol.Close, stringResource(R.string.stop_navigation), stop, size = 60.dp, iconSize = 28.dp, opacity = 0f)
        }
    }
}
