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

class HomeModel(application: Application) : AndroidViewModel(application) {
    private val catalog = CarApps(application)
    private val catalogApps = MutableStateFlow<List<CarApp>>(emptyList())
    val apps = catalogApps.asStateFlow()
    private val app = application as VeloceApplication
    val media = app.media
    val connections = app.connections
    val wirelessAuto = app.wirelessAuto
    private var refreshJob: Job? = null
    private var identityJob: Job? = null

    fun prepareIdentity() {
        if (identityJob?.isActive == true) return
        identityJob = viewModelScope.launch(Dispatchers.IO) { connections.prepare() }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                catalogApps.value = catalog.discover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                connections.log("App discovery failed: ${failure.message}")
            }
        }
    }
}
