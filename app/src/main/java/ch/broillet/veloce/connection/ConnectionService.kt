package ch.broillet.veloce.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import ch.broillet.veloce.MainActivity
import ch.broillet.veloce.R
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.apps.CarApps
import ch.broillet.veloce.projection.ProjectedWindow
import ch.broillet.veloce.projection.ScreenSpec
import ch.broillet.veloce.projection.VideoEncoder
import ch.broillet.veloce.protocol.CarLink
import ch.broillet.veloce.protocol.CarSession
import ch.broillet.veloce.protocol.CarSessionEvents
import ch.broillet.veloce.protocol.Proto
import ch.broillet.veloce.protocol.VideoMode
import ch.broillet.veloce.ui.CarLauncher
import ch.broillet.veloce.ui.CarNavigation
import ch.broillet.veloce.ui.VeloceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as VeloceApplication
    private var current: Attempt? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("car_connection", getString(R.string.connection_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") {
            current?.stop()
            app.connections.update("Disconnected", false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (current?.finished == false) return START_NOT_STICKY
        startForeground(1, notification(getString(R.string.connecting)))
        val usb = intent?.getParcelableExtra("usb", UsbAccessory::class.java)
        val address = intent?.getStringExtra("wireless")
        if (usb == null && address == null) { stopSelf(); return START_NOT_STICKY }
        val attempt = Attempt(usb, address)
        current = attempt
        scope.launch {
            attempt.execute()
            if (current === attempt) {
                if (app.connections.state.value.active) app.connections.update("Disconnected", false)
                current = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "car_connection").setSmallIcon(R.drawable.ic_connection)
            .setContentTitle(getString(R.string.app_name)).setContentText(detail).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        current?.stop()
        if (app.connections.state.value.active) app.connections.update("Disconnected", false)
        scope.cancel()
        super.onDestroy()
    }

    private inner class Attempt(usb: UsbAccessory?, private val address: String?) : CarSessionEvents {
        private val closed = AtomicBoolean(false)
        val finished: Boolean get() = closed.get()
        private val failed = AtomicBoolean(false)
        private val usbConnector = usb?.let { UsbConnector(this@ConnectionService, it) }
        private val wireless = address?.let { WirelessConnector(this@ConnectionService, it, ::status) }
        @Volatile private var link: CarLink? = null
        @Volatile private var session: CarSession? = null
        @Volatile private var encoder: VideoEncoder? = null
        @Volatile private var startedVideo = false
        @Volatile private var progress = SystemClock.elapsedRealtime()
        private var window: ProjectedWindow? = null
        @Volatile private var renderJob: Job? = null
        private val navigation = CarNavigation()

        suspend fun execute() {
            status(if (usbConnector != null) "Opening USB projection" else "Starting wireless projection")
            try {
                val tls = withContext(Dispatchers.IO) { app.connections.identity.tls() }
                withContext(Dispatchers.IO) {
                    val opened = usbConnector?.open() ?: checkNotNull(wireless).open()
                    link = opened
                    if (closed.get()) { opened.close(); return@withContext }
                    val active = CarSession(opened, tls, this@Attempt)
                    session = active
                    if (closed.get()) { active.close(); return@withContext }
                    scope.launch {
                        while (isActive && !closed.get() && !startedVideo) {
                            delay(1000)
                            if (SystemClock.elapsedRealtime() - progress > 20_000) {
                                failure(IOException("Car setup timed out: ${app.connections.state.value.detail}"))
                                break
                            }
                        }
                    }
                    active.run()
                }
            } catch (_: CancellationException) {
                // Service shutdown closes the transport before cancelling its coroutine.
            } catch (error: Exception) {
                failure(error)
            } finally {
                stop()
            }
        }

        override fun status(detail: String) {
            progress = SystemClock.elapsedRealtime()
            scope.launch {
                if (closed.get() || current !== this@Attempt) return@launch
                app.connections.update(detail, true)
                getSystemService(NotificationManager::class.java).notify(1, notification(detail))
            }
        }

        override fun video(mode: VideoMode) {
            renderJob = scope.launch {
                if (closed.get()) return@launch
                var unowned: VideoEncoder? = null
                try {
                    status("Preparing ${mode.width} × ${mode.height} car launcher")
                    val apps = CarApps(this@ConnectionService).discover()
                    withContext(Dispatchers.IO) {
                        unowned = VideoEncoder(mode, address != null, checkNotNull(session), ::failure)
                    }
                    if (closed.get()) return@launch
                    val prepared = checkNotNull(unowned)
                    encoder = prepared
                    unowned = null
                    app.connections.log("Video encoder: ${prepared.description}; density=${mode.density}, crop=${mode.cropX}x${mode.cropY}")
                    val spec = ScreenSpec(mode.width, mode.height, mode.density, mode.cropX, mode.cropY,
                        mode.touchWidth, mode.touchHeight)
                    window = ProjectedWindow(this@ConnectionService, prepared.surface, spec,
                        { navigation.back(::exit) }, navigation::home) {
                        val track by app.media.nowPlaying.collectAsStateWithLifecycle()
                        VeloceTheme { CarLauncher(spec, apps, track, navigation, app.media::control, ::exit) }
                    }
                    prepared.start()
                    startedVideo = true
                    status("Projecting launcher · ${mode.width} × ${mode.height}")
                } catch (_: CancellationException) {
                    // A disconnect may arrive while the encoder is being prepared.
                } catch (error: Exception) { failure(error) }
                finally { withContext(NonCancellable + Dispatchers.IO) { unowned?.close() } }
            }
        }

        override fun focus(projected: Boolean, inputEnabled: Boolean) {
            scope.launch {
                if (closed.get()) return@launch
                if (!inputEnabled) window?.cancelInput()
                if (projected) encoder?.keyFrame()
                status(if (projected) "Car display focus granted" else "Car display returned to native interface")
            }
        }

        override fun input(report: Proto) { scope.launch {
            if (!closed.get()) try { window?.input(report) } catch (error: Exception) { failure(error) }
        } }

        private fun exit() {
            try { session?.exitToCar() } catch (error: Exception) { failure(error) }
        }

        override fun failure(error: Exception) {
            if (closed.get() || !failed.compareAndSet(false, true)) return
            session?.close()
            scope.launch {
                if (current === this@Attempt) {
                    var cause: Throwable = error
                    while (cause.cause != null && cause.cause !== cause) cause = checkNotNull(cause.cause)
                    app.connections.update("Connection failed: ${cause.message ?: cause.javaClass.simpleName}", false, true)
                }
                stop()
            }
        }

        fun stop() {
            if (!closed.compareAndSet(false, true)) return
            renderJob?.cancel()
            session?.close()
            runCatching { link?.close() }
            usbConnector?.close()
            wireless?.close()
            runCatching { window?.close() }
            window = null
            encoder?.close()
            encoder = null
        }
    }
}
