@file:Suppress("DEPRECATION", "RestrictedApi")

package ch.broillet.veloce.hosting

import androidx.car.app.model.*
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.navigation.model.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.broillet.veloce.R
import ch.broillet.veloce.ui.CarSearchField
import ch.broillet.veloce.ui.CarSymbol
import ch.broillet.veloce.ui.GlassIconButton

@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
internal fun Template?.pageHeader(): Header? = when (this) {
    is ListTemplate -> header
    is GridTemplate -> header
    is PaneTemplate -> header
    is MessageTemplate -> header
    is MapTemplate -> header
    is PlaceListNavigationTemplate -> header
    is RoutePreviewNavigationTemplate -> header
    is MapWithContentTemplate -> contentTemplate.pageHeader()
    is TabTemplate -> if (isLoading) null else tabContents.template.pageHeader()
    else -> null
}

internal fun Template?.pageTitle(): String = when (this) {
    is LongMessageTemplate -> title.text()
    is SignInTemplate -> title.text()
    else -> pageHeader()?.title.text()
}

@Composable
internal fun RowScope.HostedToolbarActions(host: CarAppHost) {
    val template = host.template
    if (template is SearchTemplate) HostedSearch(template, host)
    val header = template.pageHeader()
    header?.startHeaderAction?.takeUnless { it.type == Action.TYPE_BACK || it.type == Action.TYPE_APP_ICON }?.let { ActionButton(it, host) }
    header?.endHeaderActions?.forEach { ActionButton(it, host) }
}

@Composable
internal fun RowScope.HostedSearch(template: SearchTemplate, host: CarAppHost) {
    var query by remember(host, template.initialSearchText) { mutableStateOf(template.initialSearchText.orEmpty()) }
    val search: (String) -> Unit = { text -> host.event { template.searchCallbackDelegate.sendSearchSubmitted(text, it) } }
    CarSearchField(query, { value ->
        query = value
        host.event { template.searchCallbackDelegate.sendSearchTextChanged(value, it) }
    }, template.searchHint ?: stringResource(R.string.search), search, Modifier.weight(1f))
    GlassIconButton(CarSymbol.Search, stringResource(R.string.search), { search(query) })
}
