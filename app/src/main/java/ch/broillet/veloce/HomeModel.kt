package ch.broillet.veloce

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.apps.CarApps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class CatalogState(
    val apps: List<CarApp> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

class HomeModel(application: Application) : AndroidViewModel(application) {
    private val catalog = CarApps(application)
    private val catalogState = MutableStateFlow(CatalogState())
    val state = catalogState.asStateFlow()
    val media = (application as VeloceApplication).media
    val connections = (application as VeloceApplication).connections
    val wirelessAuto = (application as VeloceApplication).wirelessAuto
    private var refreshJob: Job? = null
    private var identityJob: Job? = null

    fun prepareIdentity() {
        if (identityJob?.isActive == true) return
        identityJob = viewModelScope.launch(Dispatchers.IO) { connections.prepare() }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            catalogState.value = catalogState.value.copy(loading = true, failed = false)
            try {
                catalogState.value = CatalogState(catalog.discover(), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                catalogState.value = catalogState.value.copy(loading = false, failed = true)
            }
        }
    }
}
