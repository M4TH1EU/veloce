package ch.broillet.veloce.projection

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CarInputTest {
    @Test fun `touch coordinates scale and clamp into the visible video area`() {
        assertEquals(80f, mapTouch(0, 800, 1920, 160), 0.01f)
        assertEquals(960f, mapTouch(400, 800, 1920, 160), 0.01f)
        assertEquals(1837.8f, mapTouch(Long.MAX_VALUE, 800, 1920, 160), 0.01f)
        assertEquals(80f, mapTouch(-1, 800, 1920, 160), 0.01f)
    }

    @Test fun `held media keys act once and focus loss clears their state`() {
        val actions = mutableListOf<Int>()
        val buttons = CarButtons(actions::add) { fail("Unexpected assistant action") }
        val key = KeyEvent.KEYCODE_MEDIA_NEXT
        assertTrue(buttons.key(key, true))
        assertTrue(buttons.key(key, true))
        assertEquals(listOf(key), actions)
        buttons.cancel()
        assertTrue(buttons.key(key, true))
        assertTrue(buttons.key(key, false))
        assertEquals(listOf(key, key), actions)
        assertFalse(buttons.key(KeyEvent.KEYCODE_DPAD_CENTER, true))
    }
}
