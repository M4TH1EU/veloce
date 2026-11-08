package ch.broillet.veloce.projection

import android.view.KeyEvent
import ch.broillet.veloce.protocol.Proto

class CarButtons(private val media: (Int) -> Unit, private val assistant: () -> Unit) {
    private val pressed = mutableSetOf<Int>()

    fun key(code: Int, down: Boolean): Boolean {
        val voice = code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST
        if (!voice && code !in MEDIA_KEYS) return false
        if (down) {
            if (pressed.add(code)) if (voice) assistant() else media(code)
        } else pressed.remove(code)
        return true
    }

    fun cancel() = pressed.clear()

    fun backgroundInput(report: Proto) {
        report.bytes(4)?.let { encoded ->
            Proto(encoded).messages(1).forEach { event ->
                val code = event.number(1).toInt()
                if (code in MEDIA_KEYS) key(code, event.number(2) != 0L)
            }
        }
    }

    companion object {
        val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}
