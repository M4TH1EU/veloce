package ch.broillet.veloce.projection

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CarButtonsTest {
    @Test fun mediaButtonsActOncePerPressAndDoNotLeakIntoTheUi() {
        val actions = mutableListOf<Int>()
        val buttons = CarButtons(actions::add) { fail("Not an assistant action") }
        CarButtons.MEDIA_KEYS.forEach { key ->
            assertTrue(buttons.key(key, true))
            assertTrue(buttons.key(key, true))
            assertTrue(buttons.key(key, false))
        }
        assertEquals(CarButtons.MEDIA_KEYS.toList(), actions)
        assertFalse(buttons.key(KeyEvent.KEYCODE_DPAD_CENTER, true))
        assertFalse(buttons.key(KeyEvent.KEYCODE_BACK, false))
    }

    @Test fun assistantVariantsAreHandledWithoutStartingAnything() {
        var assistant = 0
        val buttons = CarButtons({ fail("Not a media action") }) { assistant++ }
        listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST).forEach {
            assertTrue(buttons.key(it, true))
            assertTrue(buttons.key(it, true))
            assertTrue(buttons.key(it, false))
        }
        assertEquals(3, assistant)
        buttons.key(KeyEvent.KEYCODE_ASSIST, false)
        assertEquals(3, assistant)
    }

    @Test fun focusLossClearsAnUnreleasedButton() {
        var presses = 0
        val buttons = CarButtons({ presses++ }, {})
        buttons.key(KeyEvent.KEYCODE_MEDIA_NEXT, true)
        buttons.cancel()
        buttons.key(KeyEvent.KEYCODE_MEDIA_NEXT, true)
        assertEquals(2, presses)
    }
}
