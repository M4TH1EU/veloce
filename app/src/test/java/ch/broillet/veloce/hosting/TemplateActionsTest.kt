@file:Suppress("DEPRECATION", "RestrictedApi")

package ch.broillet.veloce.hosting

import android.app.Application
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TemplateActionsTest {
    @Test fun legacyActionStripIsNotRenderedAgainWhenExposedByTheModernHeader() {
        val close = Action.Builder().setTitle("Close").setOnClickListener {}.build()
        val strip = ActionStrip.Builder().addAction(close).build()
        val pane = Pane.Builder().addRow(Row.Builder().setTitle("Destination").build()).build()
        val template = PaneTemplate.Builder(pane).setTitle("Start now").setActionStrip(strip).build()
        assertEquals(listOf(close), template.header!!.endHeaderActions)
        assertTrue(footerActions(template.header, strip).isEmpty())
        val navigate = Action.Builder().setTitle("Navigate").setOnClickListener {}.build()
        assertEquals(listOf(navigate), footerActions(template.header, strip, listOf(navigate)))
    }
}
