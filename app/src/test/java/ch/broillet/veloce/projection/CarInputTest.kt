package ch.broillet.veloce.projection

import android.view.KeyEvent
import ch.broillet.veloce.protocol.Proto
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

    @Test fun `screen off input keeps media controls but ignores navigation touch and voice keys`() {
        val actions = mutableListOf<Int>()
        val buttons = CarButtons(actions::add) { fail("Background input invoked the assistant") }
        fun report(down: Boolean) = Proto(Proto.build {
            bytes(3, Proto.build { number(3, 0) })
            bytes(4, Proto.build {
                listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ASSIST).forEach { code ->
                    bytes(1, Proto.build { number(1, code.toLong()); number(2, if (down) 1 else 0) })
                }
            })
        })
        buttons.backgroundInput(report(true))
        buttons.backgroundInput(report(true))
        assertEquals(listOf(87, 88, 85), actions)
        buttons.backgroundInput(report(false))
        buttons.backgroundInput(report(true))
        assertEquals(listOf(87, 88, 85, 87, 88, 85), actions)
    }
}
