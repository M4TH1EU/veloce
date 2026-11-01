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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import ch.broillet.veloce.lyrics.LyricsCacheInfo

class HomeModel(application: Application) : AndroidViewModel(application) {
    private val catalog = CarApps(application)
    private val catalogApps = MutableStateFlow<List<CarApp>>(emptyList())
    val apps = catalogApps.asStateFlow()
    private val discovering = MutableStateFlow(false)
    val refreshingApps = discovering.asStateFlow()
    private val app = application as VeloceApplication
    val media = app.media
    val connections = app.connections
    val wirelessAuto = app.wirelessAuto
    val settings = app.settings
    private val cacheInfo = MutableStateFlow(LyricsCacheInfo())
    val lyricsCache = cacheInfo.asStateFlow()
    private val clearingCache = MutableStateFlow(false)
    val clearingLyrics = clearingCache.asStateFlow()
    private var refreshJob: Job? = null
    private var identityJob: Job? = null

    fun refreshLyricsCache() {
        viewModelScope.launch { cacheInfo.value = withContext(Dispatchers.IO) { app.lyrics.cacheInfo() } }
    }

    fun clearLyricsCache(result: (Boolean) -> Unit) {
        if (clearingCache.value) return
        clearingCache.value = true
        viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) { app.lyrics.clearCache() }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                connections.log("Could not clear lyrics cache: ${failure.javaClass.simpleName}")
                false
            } finally {
                cacheInfo.value = withContext(NonCancellable + Dispatchers.IO) { app.lyrics.cacheInfo() }
                clearingCache.value = false
            }
            result(success)
        }
    }

    fun prepareIdentity() {
        if (identityJob?.isActive == true) return
        identityJob = viewModelScope.launch(Dispatchers.IO) { connections.prepare() }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            discovering.value = true
            try {
                catalogApps.value = catalog.discover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                connections.log("App discovery failed: ${failure.message}")
            } finally {
                discovering.value = false
            }
        }
    }
}
