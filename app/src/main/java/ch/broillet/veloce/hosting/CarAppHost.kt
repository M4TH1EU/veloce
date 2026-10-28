@file:Suppress("RestrictedApi")

package ch.broillet.veloce.hosting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.location.Location
import android.os.Binder
import android.os.IBinder
import androidx.car.app.*
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.model.*
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.INavigationManager
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.serialization.Bundleable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.broillet.veloce.apps.CarApp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.UUID

class CarAppHost(
    displayContext: Context,
    val app: CarApp,
    private val finish: () -> Unit,
    private val log: (String) -> Unit,
    private val home: () -> Unit = finish,
) : Closeable {
    private val context = displayContext.applicationContext
    private val configuration = Configuration(displayContext.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val appCalls = Mutex()
    private val surfaceCalls = Mutex()
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    private var remote: ICarApp? = null
    private var manager: IAppManager? = null
    private var navigation: INavigationManager? = null
    private var bound = false
    @Volatile private var closed = false
    private var created = false
    private var started = false
    private var resumed = false
    private var visible = true
    private var surfaceCallback: ISurfaceCallback? = null
    private var surface: SurfaceContainer? = null
    private val ownedSurfaces = mutableSetOf<SurfaceContainer>()
    private var visibleArea = Rect()
    private var alertTimer: Job? = null
    var navigating by mutableStateOf(false)
        private set
    var template by mutableStateOf<Template?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var alert by mutableStateOf<Alert?>(null)
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            if (remote != null) { fail(IllegalStateException("App service reconnected; reopen the app")); return }
            val endpoint = ICarApp.Stub.asInterface(binder)
            remote = endpoint
            scope.launch {
                try {
                    lifecycle.withLock {
                        val info = request { endpoint.getAppInfo(it) } as? AppInfo
                            ?: error("App did not return AndroidX AppInfo")
                        val api = HostApi.negotiate(info.minCarAppApiLevel, info.latestCarAppApiLevel)
                        log("Car app ${app.component.flattenToShortString()}: library=${info.libraryDisplayVersion}, API=$api")
                        request { endpoint.onHandshakeCompleted(Bundleable.create(HandshakeInfo(context.packageName, api)), it) }
                        request { endpoint.onAppCreate(carHost, Intent(Intent.ACTION_MAIN).setComponent(app.component), configuration, it) }
                        created = true
                        manager = request { endpoint.getManager(CarContext.APP_SERVICE, it) } as? IAppManager
                            ?: error("App did not return an AndroidX IAppManager")
                        navigation = request { endpoint.getManager(CarContext.NAVIGATION_SERVICE, it) } as? INavigationManager
                            ?: error("App did not return an AndroidX INavigationManager")
                        updateLifecycle()
                    }
                    for (ignored in invalidations) {
                        if (!started || !visible) continue
                        val result = request { checkNotNull(manager).getTemplate(it) }
                        template = (result as? TemplateWrapper)?.template ?: error("App did not return a template")
                        log("Car app template: ${template?.javaClass?.simpleName}")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { fail(IllegalStateException("App service disconnected; reopen the app")) }
        override fun onBindingDied(name: ComponentName) { fail(IllegalStateException("App binding died; reopen the app")) }
        override fun onNullBinding(name: ComponentName) { fail(IllegalStateException("App refused the car service binding")) }
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        check(uid == context.applicationInfo.uid || uid == context.packageManager.getApplicationInfo(app.component.packageName, 0).uid) {
            "Caller does not own the selected car app"
        }
    }

    private fun fromApp(block: () -> Unit) {
        enforceCaller()
        if (closed) return
        scope.launch { if (!closed) try { block() } catch (failure: Exception) { fail(failure) } }
    }

    private val appHost = object : IAppHost.Stub() {
        override fun invalidate() = fromApp { invalidations.trySend(Unit) }
        override fun showToast(text: CharSequence, duration: Int) = fromApp { notice = text.toString().take(500) }
        override fun setSurfaceCallback(callback: ISurfaceCallback?) = fromApp {
            val old = surfaceCallback
            val target = surface
            if (old != null && target != null) surfaceCall { request { old.onSurfaceDestroyed(Bundleable.create(target), it) } }
            surfaceCallback = callback
            if (callback != null && target != null) deliverSurface(callback, target)
        }
        override fun sendLocation(location: Location?) { enforceCaller() }
        override fun showAlert(bundle: Bundleable) = fromApp {
            val incoming = bundle.get() as Alert
            if (!visible || template !is NavigationTemplate) {
                incoming.callbackDelegate?.let { delegate ->
                    event { delegate.sendCancel(AlertCallback.REASON_NOT_SUPPORTED, it) }
                    event { delegate.sendDismiss(it) }
                }
            } else if (alert?.id != incoming.id) {
                dismissAlert()
                alert = incoming
                if (incoming.durationMillis > 0) alertTimer = scope.launch {
                    delay(incoming.durationMillis)
                    dismissAlert(AlertCallback.REASON_TIMEOUT)
                }
            }
        }
        override fun dismissAlert(alertId: Int) = fromApp { if (alert?.id == alertId) dismissAlert() }
        override fun openMicrophone(request: Bundleable?): Bundleable? {
            enforceCaller()
            fromApp { notice = "Car microphone is not implemented" }
            return null
        }
    }

    private val navigationHost = object : INavigationHost.Stub() {
        override fun navigationStarted() = fromApp { navigating = true; log("Car app navigation started") }
        override fun navigationEnded() = fromApp { navigating = false; log("Car app navigation ended") }
        override fun updateTrip(trip: Bundleable?) { enforceCaller() } // Cluster forwarding is not advertised.
    }
    private val constraints = object : IConstraintHost.Stub() {
        override fun getContentLimit(type: Int): Int {
            enforceCaller()
            return when (type) { 1 -> 6; 2 -> 6; 3 -> 3; 4 -> 4; else -> 6 }
        }
        override fun isAppDrivenRefreshEnabled(): Boolean { enforceCaller(); return true }
    }
    private val carHost = object : ICarHost.Stub() {
        override fun getHost(type: String): IBinder? {
            enforceCaller()
            if (closed) return null
            return when (type) {
                CarContext.APP_SERVICE -> appHost
                CarContext.NAVIGATION_SERVICE -> navigationHost
                CarContext.CONSTRAINT_SERVICE -> constraints
                else -> null
            }
        }
        override fun finish() = fromApp { finish.invoke() }
        override fun startCarApp(intent: Intent) = fromApp {
            if ((intent.component?.packageName ?: intent.`package` ?: app.component.packageName) != app.component.packageName) {
                notice = "Opening another app from a car app is not implemented"
            } else scope.launch {
                try { appCalls.withLock { request { checkNotNull(remote).onNewIntent(intent, it) } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure) }
            }
        }
    }

    fun connect() {
        check(!bound && !closed)
        val intent = Intent(CarAppService.SERVICE_INTERFACE).setComponent(app.component)
        SessionInfoIntentEncoder.encode(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, UUID.randomUUID().toString()), intent)
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Could not bind to ${app.label}" }
            scope.launch { delay(10_000); if (remote == null) fail(IllegalStateException("Timed out binding to ${app.label}")) }
        } catch (failure: Exception) { fail(failure) }
    }

    fun setVisible(value: Boolean) {
        if (closed || visible == value) return
        visible = value
        if (!value) dismissAlert(AlertCallback.REASON_NOT_SUPPORTED)
        scope.launch {
            try { lifecycle.withLock { updateLifecycle() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private suspend fun updateLifecycle() {
        val endpoint = remote ?: return
        if (!created) return
        if (visible) {
            if (!started) { request { endpoint.onAppStart(it) }; started = true }
            if (!resumed) { request { endpoint.onAppResume(it) }; resumed = true }
            invalidations.trySend(Unit)
        } else {
            if (resumed) { request { endpoint.onAppPause(it) }; resumed = false }
            if (started) { request { endpoint.onAppStop(it) }; started = false }
        }
    }

    fun back() {
        val endpoint = manager ?: return
        scope.launch {
            try { appCalls.withLock { request { endpoint.onBackPressed(it) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun click(action: Action) {
        when (action.type) {
            Action.TYPE_BACK -> back()
            Action.TYPE_APP_ICON -> home()
            else -> click(action.onClickDelegate)
        }
    }

    fun click(delegate: OnClickDelegate?) {
        if (delegate == null) return
        event { delegate.sendClick(it) }
    }

    fun event(call: (OnDoneCallback) -> Unit) {
        if (closed) return
        scope.launch {
            try { appCalls.withLock { request { call(it.asDoneCallback()) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun dismissNotice() { notice = null }

    fun stopNavigation() {
        val endpoint = navigation ?: return
        scope.launch {
            try {
                appCalls.withLock { request { endpoint.onStopNavigation(it) } }
                navigating = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun dismissAlert(reason: Int? = null) {
        val previous = alert ?: return
        alert = null
        alertTimer?.cancel()
        previous.callbackDelegate?.let { delegate ->
            if (reason != null) event { delegate.sendCancel(reason, it) }
            event { delegate.sendDismiss(it) }
        }
    }

    fun surfaceAvailable(target: SurfaceContainer) {
        ownedSurfaces.add(target)
        surface = target
        surfaceCallback?.let { deliverSurface(it, target) }
    }

    private fun deliverSurface(callback: ISurfaceCallback, target: SurfaceContainer) {
        val area = Rect(visibleArea)
        surfaceCall {
            request { callback.onSurfaceAvailable(Bundleable.create(target), it) }
            request { callback.onVisibleAreaChanged(area, it) }
            request { callback.onStableAreaChanged(area, it) }
        }
    }

    fun surfaceArea(area: Rect) {
        if (area == visibleArea) return
        visibleArea = Rect(area)
        val callback = surfaceCallback ?: return
        if (surface == null) return
        surfaceCall {
            request { callback.onVisibleAreaChanged(area, it) }
            request { callback.onStableAreaChanged(area, it) }
        }
    }

    fun surfaceDestroyed(target: SurfaceContainer) {
        if (closed) { target.surface?.release(); ownedSurfaces.remove(target); return }
        if (surface === target) surface = null
        val callback = surfaceCallback
        surfaceCall {
            try { if (callback != null) request { callback.onSurfaceDestroyed(Bundleable.create(target), it) } }
            finally { target.surface?.release(); ownedSurfaces.remove(target) }
        }
    }

    fun gesture(call: (ISurfaceCallback) -> Unit) {
        val callback = surfaceCallback ?: return
        surfaceCall { withContext(Dispatchers.IO) { call(callback) } }
    }

    private fun surfaceCall(call: suspend () -> Unit) {
        if (closed) return
        scope.launch {
            try { surfaceCalls.withLock { call() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private suspend fun request(call: (IOnDoneCallback) -> Unit): Any? = try { withTimeout(8_000) {
        val result = CompletableDeferred<Any?>()
        val callback = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                if (!result.isActive) return
                try { result.complete(response?.get()) } catch (failure: Exception) { result.completeExceptionally(failure) }
            }
            override fun onFailure(response: Bundleable?) {
                if (!result.isActive) return
                val details = runCatching { (response?.get() as? FailureResponse)?.stackTrace }.getOrNull()
                result.completeExceptionally(IllegalStateException(details ?: "Car app rejected the host request"))
            }
        }
        try {
            withContext(Dispatchers.IO) { call(callback) }
            result.await()
        } finally { result.cancel() }
    } } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Car app did not respond within 8 seconds", timeout)
    }

    private fun IOnDoneCallback.asDoneCallback() = object : OnDoneCallback {
        override fun onSuccess(response: Bundleable?) = this@asDoneCallback.onSuccess(response)
        override fun onFailure(response: Bundleable) = this@asDoneCallback.onFailure(response)
    }

    private fun fail(failure: Exception) {
        if (closed) return
        log("Car app ${app.label}: ${failure.message ?: failure.javaClass.simpleName}")
        error = failure.message?.lineSequence()?.firstOrNull() ?: failure.javaClass.simpleName
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.coroutineContext.cancelChildren()
        val endpoint = remote
        val appNavigation = navigation
        val callback = surfaceCallback
        val target = surface
        val wasNavigating = navigating
        navigating = false
        scope.launch {
            try { withTimeoutOrNull(2_000) {
                if (callback != null && target != null) runCatching { request { callback.onSurfaceDestroyed(Bundleable.create(target), it) } }
                if (endpoint != null && created) {
                    if (wasNavigating && appNavigation != null) runCatching { request { appNavigation.onStopNavigation(it) } }
                    if (resumed) runCatching { request { endpoint.onAppPause(it) } }
                    if (started) runCatching { request { endpoint.onAppStop(it) } }
                }
            }
            } finally {
                ownedSurfaces.forEach { it.surface?.release() }
                ownedSurfaces.clear()
                if (bound) context.unbindService(connection)
                bound = false
                scope.cancel()
            }
        }
    }
}
