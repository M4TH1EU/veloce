package ch.broillet.veloce.connection

import android.Manifest
import android.app.Activity
import android.content.Intent
import ch.broillet.veloce.VeloceApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloceApplication::class)
class ConnectionSetupActivityTest {
    private val app = RuntimeEnvironment.getApplication() as VeloceApplication

    private fun begin() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        app.connections.begin("drive")
    }

    @Test fun connectionPreparationStartsLocationWithoutWaitingForTheHeadUnitOrOpeningThePhoneUi() {
        begin()
        val controller = Robolectric.buildActivity(ConnectionSetupActivity::class.java,
            ConnectionSetupActivity.intent(app, "drive", audio = false)).setup()
        try {
            assertTrue(controller.get().isFinishing)
            assertEquals("prepare", shadowOf(app).nextStartedService.action)
            assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun audioAuthorizationCanCompleteWithoutAMainActivityAndIsHandedToTheOwningDrive() {
        begin()
        app.connections.audio("Ready", request = "drive")
        val controller = Robolectric.buildActivity(ConnectionSetupActivity::class.java,
            ConnectionSetupActivity.intent(app, "drive")).setup()
        try {
            val activity = controller.get()
            val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals("prepare", shadowOf(app).nextStartedService.action)
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().putExtra("test-token", true))
            val start = checkNotNull(shadowOf(app).nextStartedService)
            assertEquals("audio", start.action)
            assertEquals("drive", start.getStringExtra("session"))
            assertEquals(Activity.RESULT_OK, start.getIntExtra("result", 0))
            assertTrue(checkNotNull(start.getParcelableExtra("consent", Intent::class.java)).getBooleanExtra("test-token", false))
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun approvalFromADisconnectedDriveCannotStartCaptureInItsReplacement() {
        begin()
        app.connections.audio("Ready", request = "drive")
        val controller = Robolectric.buildActivity(ConnectionSetupActivity::class.java,
            ConnectionSetupActivity.intent(app, "drive")).setup()
        try {
            val activity = controller.get()
            val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals("prepare", shadowOf(app).nextStartedService.action)
            app.connections.begin("replacement")
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent())
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
}
