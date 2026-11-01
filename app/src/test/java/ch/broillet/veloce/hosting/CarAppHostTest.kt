@file:Suppress("RestrictedApi")

package ch.broillet.veloce.hosting

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.view.Surface
import androidx.car.app.*
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Toggle
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationManager
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.serialization.Bundleable
import androidx.car.app.serialization.Bundler
import androidx.car.app.hardware.common.CarHardwareHostDispatcher
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.ProjectedCarInfo
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.ui.CarNavigation
import ch.broillet.veloce.ui.CarWorkspace
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarAppHostTest {
    private val endpoint = AppEndpoint()
    private val logs = mutableListOf<String>()
    private lateinit var host: CarAppHost

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context.packageName, "TestCarService")
        shadowOf(context).setComponentNameAndServiceForBindService(component, endpoint)
        host = CarAppHost(context, CarApp(component, "Navigation", AppKind.MAP), {}, logs::add)
    }

    @After fun cleanup() {
        host.close()
        await { shadowOf(RuntimeEnvironment.getApplication()).unboundServiceConnections.isNotEmpty() }
    }

    @Test fun remoteHostNegotiatesRendersAndHandlesLifecycleAndInput() {
        host.connect()
        await { host.template != null || host.error != null }
        assertNull(host.error)
        assertEquals(listOf("info", "handshake:7", "create", "manager:app", "manager:navigation", "start", "resume", "template"), endpoint.calls)
        assertTrue(host.template is MessageTemplate)
        assertTrue(logs.any { "API=7" in it })
        assertEquals(Configuration.UI_MODE_NIGHT_YES, endpoint.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertTrue(endpoint.managerResponses[0] is IAppManager)
        assertTrue(endpoint.managerResponses[1] is INavigationManager)
        assertTrue(endpoint.managerResponses.none { it is IBinder })
        val appHost = IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE))
        appHost.invalidate()
        await { endpoint.calls.count { it == "template" } == 2 }
        host.back()
        await { "back" in endpoint.calls }
        host.setVisible(false)
        await { "stop" in endpoint.calls }
        val count = endpoint.calls.count { it == "template" }
        appHost.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, endpoint.calls.count { it == "template" })
        host.setVisible(true)
        await { endpoint.calls.count { it == "template" } == count + 1 }
        assertEquals(listOf("pause", "stop", "start", "resume", "template"), endpoint.calls.takeLast(5))
        host.close()
        await { endpoint.calls.takeLast(2) == listOf("pause", "stop") }
        appHost.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count + 1, endpoint.calls.count { it == "template" })
    }

    @Test fun hostRejectionIsVisibleAndUnbinds() {
        endpoint.rejectHandshake = true
        host.connect()
        await { host.error != null }
        assertTrue(host.error!!.contains("Unknown host"))
        assertFalse("create" in endpoint.calls)
        assertTrue(logs.any { "Unknown host" in it })
    }

    @Test fun nativeBackActionsCanUnwindMultipleHostedScreens() {
        host.connect()
        await { host.template != null }
        host.click(Action.BACK)
        await { endpoint.calls.count { it == "back" } == 1 }
        host.click(Action.BACK)
        await { endpoint.calls.count { it == "back" } == 2 }
        assertNull(host.error)
    }

    @Test fun refreshedTemplatesReplaceCallbacksEvenWhenTheirVisualModelsCompareEqual() {
        var first = 0
        var second = 0
        fun options(change: (Boolean) -> Unit): Template = ListTemplate.Builder().setTitle("Route options")
            .setSingleList(ItemList.Builder().addItem(Row.Builder().setTitle("Avoid highways")
                .setToggle(Toggle.Builder { change(it) }.build()).build()).build()).build()
        endpoint.template = options { first++ }
        host.connect()
        await { host.template != null }
        val original = host.template!!
        endpoint.template = options { second++ }
        assertEquals(original, endpoint.template)
        val appHost = IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE))
        appHost.invalidate()
        await { host.template !== original }
        val toggle = ((host.template as ListTemplate).singleList!!.items.first() as Row).toggle!!
        host.event { toggle.onCheckedChangeDelegate.sendCheckedChange(true, it) }
        await { second == 1 }
        assertEquals(0, first)
    }

    @Test fun homeAndMusicRetainTheSameNavigationSession() {
        val navigation = CarNavigation()
        val workspace = CarWorkspace(RuntimeEnvironment.getApplication(), navigation, logs::add)
        try {
            workspace.open(host.app)
            val retained = workspace.host!!
            await { retained.template != null }
            INavigationHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.NAVIGATION_SERVICE)).navigationStarted()
            await { retained.navigating }
            navigation.home()
            workspace.updateVisibility()
            await { "stop" in endpoint.calls }
            assertTrue(retained.navigating)
            assertFalse("endNavigation" in endpoint.calls)
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).unboundServiceConnections.isEmpty())
            workspace.open(host.app.copy(component = ComponentName(host.app.component.packageName, "Music"), kind = AppKind.AUDIO))
            assertSame(retained, workspace.host)
            workspace.open(host.app)
            await { endpoint.calls.count { it == "resume" } == 2 }
            assertEquals(1, endpoint.calls.count { it == "create" })
        } finally { workspace.close() }
        await { "endNavigation" in endpoint.calls }
    }

    @Test fun newRoutesStartFullscreenButPageChangesAndDuplicateStartEventsKeepTheChosenLayout() {
        val context = RuntimeEnvironment.getApplication()
        val navigation = CarNavigation()
        val workspace = CarWorkspace(context, navigation, logs::add)
        try {
            workspace.setSplitLayout(true)
            workspace.open(host.app)
            val retained = workspace.host!!
            await { retained.template != null }
            val navigationHost = INavigationHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.NAVIGATION_SERVICE))
            val appHost = IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE))
            navigationHost.navigationStarted()
            await { retained.navigating }
            assertFalse(workspace.splitScreen)
            workspace.setSplitLayout(true)
            navigation.home()
            workspace.updateVisibility()
            await { "stop" in endpoint.calls }
            workspace.open(host.app)
            await { endpoint.calls.count { it == "resume" } == 2 }
            await { endpoint.calls.count { it == "template" } == 2 }
            assertTrue(workspace.splitScreen)
            val count = endpoint.calls.count { it == "template" }
            navigationHost.navigationStarted()
            appHost.invalidate()
            await { endpoint.calls.count { it == "template" } > count }
            assertTrue(workspace.splitScreen)
            navigationHost.navigationEnded()
            await { !retained.navigating }
            navigationHost.navigationStarted()
            await { retained.navigating }
            assertFalse(workspace.splitScreen)
            assertFalse(context.getSharedPreferences("launcher", Application.MODE_PRIVATE).getBoolean("split_screen", true))
            assertSame(retained, workspace.host)
            assertEquals(1, endpoint.calls.count { it == "create" })
        } finally { workspace.close() }
    }

    @Test fun dismissedNoticesStayHiddenUntilTheMessageChangesOrANewRouteStarts() {
        host.connect()
        await { host.template != null }
        val appHost = IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE))
        appHost.showToast("No GPS signal", 1)
        await { host.notice != null }
        host.dismissNotice()
        appHost.showToast("No GPS signal", 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(host.notice)
        appHost.showToast("GPS signal restored", 1)
        await { host.notice == "GPS signal restored" }
        appHost.showToast("No GPS signal", 1)
        await { host.notice == "No GPS signal" }
        host.dismissNotice()
        INavigationHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.NAVIGATION_SERVICE)).navigationStarted()
        await { host.navigating }
        appHost.showToast("No GPS signal", 1)
        await { host.notice == "No GPS signal" }
    }

    @Test fun userDismissedAlertsStayHiddenAcrossIdsButChangedAndTimedOutAlertsCanShow() {
        endpoint.template = NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Options").setOnClickListener {}.build()).build()).build()
        host.connect()
        await { host.template != null }
        val appHost = IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE))
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun warning(id: Int, title: String = "No GPS signal"): Bundleable = Bundleable.create(
            Alert.Builder(id, CarText.create(title), Alert.DURATION_SHOW_INDEFINITELY.toLong())
                .setCallback(object : AlertCallback {
                    override fun onCancel(reason: Int) { received.add("$id:cancel:$reason") }
                    override fun onDismiss() { received.add("$id:dismiss") }
                }).build())
        appHost.showAlert(warning(1))
        await { host.alert?.id == 1 }
        host.dismissAlert(AlertCallback.REASON_USER_ACTION)
        await { received.size == 2 }
        assertEquals(listOf("1:cancel:2", "1:dismiss"), received.toList())
        appHost.showAlert(warning(2))
        await { received.size == 4 }
        assertNull(host.alert)
        assertEquals(listOf("2:cancel:2", "2:dismiss"), received.toList().takeLast(2))
        appHost.showAlert(warning(3, "Recalculating route"))
        await { host.alert?.id == 3 }
        host.dismissAlert(AlertCallback.REASON_TIMEOUT)
        await { received.size == 6 }
        assertEquals(listOf("3:cancel:1", "3:dismiss"), received.toList().takeLast(2))
        appHost.showAlert(warning(4, "Recalculating route"))
        await { host.alert?.id == 4 }
        host.dismissAlert(userInitiated = true)
        await { received.size == 7 }
        assertEquals("4:dismiss", received.last())
        appHost.showAlert(warning(5, "Recalculating route"))
        await { received.size == 9 }
        assertNull(host.alert)
        INavigationHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.NAVIGATION_SERVICE)).navigationStarted()
        await { host.navigating }
        appHost.showAlert(warning(6, "Recalculating route"))
        await { host.alert?.id == 6 }
        assertNull(host.error)
    }

    @Test fun surfaceRegisteredBeforeCallbackGetsDimensionsBoundsAndGestures() {
        host.connect()
        await { host.template != null }
        var target = SurfaceContainer(Surface(SurfaceTexture(0)), 640, 360, 160)
        host.surfaceArea(Rect(0, 0, 640, 360))
        host.surfaceAvailable(target)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val callback = object : ISurfaceCallback.Stub() {
            override fun onSurfaceAvailable(bundle: Bundleable, done: IOnDoneCallback) {
                val container = bundle.get() as SurfaceContainer
                received.add("available:${container.width}x${container.height}@${container.dpi}")
                done.onSuccess(null)
            }
            override fun onVisibleAreaChanged(area: Rect, done: IOnDoneCallback) { received.add("visible:${area.width()}x${area.height()}"); done.onSuccess(null) }
            override fun onStableAreaChanged(area: Rect, done: IOnDoneCallback) { received.add("stable:${area.width()}x${area.height()}"); done.onSuccess(null) }
            override fun onSurfaceDestroyed(bundle: Bundleable, done: IOnDoneCallback) { received.add("destroyed"); done.onSuccess(null) }
            override fun onScroll(x: Float, y: Float) { received.add("scroll:$x,$y") }
            override fun onFling(x: Float, y: Float) = Unit
            override fun onScale(x: Float, y: Float, factor: Float) = Unit
            override fun onClick(x: Float, y: Float) { received.add("click:$x,$y") }
        }
        IAppHost.Stub.asInterface(endpoint.carHost.getHost(CarContext.APP_SERVICE)).setSurfaceCallback(callback)
        await { received.size == 3 || host.error != null }
        assertNull(host.error)
        assertEquals(listOf("available:640x360@160", "visible:640x360", "stable:640x360"), received.toList())
        host.gesture { it.onScroll(12f, 5f) }
        await { received.size == 4 }
        host.surfaceArea(Rect(100, 80, 560, 280), Rect(80, 40, 560, 320))
        await { received.size == 6 }
        assertEquals(listOf("visible:460x200", "stable:480x280"), received.toList().takeLast(2))
        host.surfaceArea(Rect(100, 80, 560, 280), Rect(80, 40, 560, 300))
        await { received.size == 8 }
        assertEquals("stable:480x260", received.last())
        for (width in listOf(416, 640)) {
            val count = received.size
            host.surfaceDestroyed(target)
            host.surfaceArea(Rect(104, 16, width - 16, 344), Rect(104, 80, width - 80, 344))
            target = SurfaceContainer(Surface(SurfaceTexture(0)), width, 360, 160)
            host.surfaceAvailable(target)
            await { received.size == count + 4 }
            assertEquals(listOf("destroyed", "available:${width}x360@160", "visible:${width - 120}x328", "stable:${width - 184}x264"),
                received.toList().takeLast(4))
        }
        host.gesture { it.onClick(210f, 180f) }
        await { received.last() == "click:210.0,180.0" }
        assertEquals(1, endpoint.calls.count { it == "create" })
        assertFalse("stop" in endpoint.calls)
        val count = received.size
        host.surfaceDestroyed(target)
        await { received.size == count + 1 }
        assertEquals("scroll:12.0,5.0", received[3])
    }

    @Test fun nonNavigationAppsDoNotNeedANavigationManagerAndHardwareReportsUnsupported() {
        val context = RuntimeEnvironment.getApplication()
        host.close()
        host = CarAppHost(context, host.app.copy(kind = AppKind.OTHER), {}, logs::add)
        host.connect()
        await { host.template != null }
        assertFalse("manager:navigation" in endpoint.calls)
        val dispatcher = HostDispatcher().apply { setCarHost(endpoint.carHost) }
        val info = ProjectedCarInfo(CarHardwareHostDispatcher(dispatcher))
        var model: Model? = null
        info.fetchModel({ it.run() }) { model = it }
        await { model != null || host.error != null }
        assertNull(host.error)
        assertNull(model!!.manufacturer.value)
    }

    @Test fun configurationChangesReachTheSameSessionAndDoNotRecreateIt() {
        host.connect()
        await { host.template != null }
        val changed = Configuration(endpoint.configuration).apply { screenWidthDp = 800 }
        host.updateConfiguration(changed)
        await { "configuration" in endpoint.calls }
        assertEquals(800, endpoint.configuration.screenWidthDp)
        host.updateConfiguration(changed)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, endpoint.calls.count { it == "configuration" })
        assertEquals(1, endpoint.calls.count { it == "create" })
    }

    private fun await(condition: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out. Calls=${endpoint.calls}, error=${host.error}")
    }

    private class AppEndpoint : ICarApp.Stub() {
        val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        var rejectHandshake = false
        val managerResponses = mutableListOf<Any>()
        lateinit var carHost: ICarHost
        lateinit var configuration: Configuration
        var template: Template = MessageTemplate.Builder("Map ready").build()
        private val appManager = object : IAppManager.Stub() {
            override fun getTemplate(callback: IOnDoneCallback) {
                calls.add("template")
                callback.onSuccess(Bundleable.create(TemplateWrapper.wrap(template)))
            }
            override fun onBackPressed(callback: IOnDoneCallback) { calls.add("back"); callback.onSuccess(null) }
            override fun startLocationUpdates(callback: IOnDoneCallback) = callback.onSuccess(null)
            override fun stopLocationUpdates(callback: IOnDoneCallback) = callback.onSuccess(null)
        }
        private val navigation = object : INavigationManager.Stub() {
            override fun onStopNavigation(callback: IOnDoneCallback) { calls.add("endNavigation"); callback.onSuccess(null) }
        }
        override fun getAppInfo(callback: IOnDoneCallback) {
            calls.add("info")
            callback.onSuccess(Bundleable.create(AppInfo(1, 7, "test")))
        }
        override fun onHandshakeCompleted(info: Bundleable, callback: IOnDoneCallback) {
            calls.add("handshake:${(info.get() as HandshakeInfo).hostCarAppApiLevel}")
            if (rejectHandshake) callback.onFailure(Bundleable.create(FailureResponse(IllegalArgumentException("Unknown host"))))
            else callback.onSuccess(null)
        }
        override fun onAppCreate(host: ICarHost, intent: Intent, configuration: Configuration, callback: IOnDoneCallback) {
            carHost = host
            this.configuration = configuration
            calls.add("create")
            callback.onSuccess(null)
        }
        override fun getManager(type: String, callback: IOnDoneCallback) {
            calls.add("manager:$type")
            val manager = if (type == CarContext.APP_SERVICE) appManager else navigation
            val response = remoteResponse(manager)
            managerResponses.add(response.get())
            callback.onSuccess(response)
        }

        private fun remoteResponse(manager: IInterface): Bundleable {
            // Force Stub.asInterface to create a Proxy, as it does across Android processes.
            val binder = object : Binder() {
                override fun queryLocalInterface(descriptor: String): IInterface? = null
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    manager.asBinder().transact(code, data, reply, flags)
            }
            val bundle = Bundler.toBundle(manager).apply { putBinder("tag_value", binder) }
            val parcel = Parcel.obtain()
            return try {
                parcel.writeBundle(bundle)
                parcel.setDataPosition(0)
                Bundleable.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        override fun onAppStart(callback: IOnDoneCallback) { calls.add("start"); callback.onSuccess(null) }
        override fun onAppResume(callback: IOnDoneCallback) { calls.add("resume"); callback.onSuccess(null) }
        override fun onAppPause(callback: IOnDoneCallback) { calls.add("pause"); callback.onSuccess(null) }
        override fun onAppStop(callback: IOnDoneCallback) { calls.add("stop"); callback.onSuccess(null) }
        override fun onNewIntent(intent: Intent, callback: IOnDoneCallback) = callback.onSuccess(null)
        override fun onConfigurationChanged(configuration: Configuration, callback: IOnDoneCallback) {
            this.configuration = configuration
            calls.add("configuration")
            callback.onSuccess(null)
        }
    }
}
