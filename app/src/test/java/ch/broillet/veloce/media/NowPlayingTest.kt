package ch.broillet.veloce.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {
    private val track = NowPlaying("music", "Title", "Artist", playing = true, actions = 0,
        positionMs = 30_000, durationMs = 180_000, updatedAtMs = 10_000)

    @Test fun progressUsesTheSessionsMonotonicTimestampAndPlaybackSpeed() {
        assertEquals(35_000, track.positionAt(15_000))
        assertEquals(37_500, track.copy(speed = 1.5f).positionAt(15_000))
        assertEquals(180_000, track.positionAt(300_000))
        assertEquals(30_000, track.positionAt(5_000))
    }

    @Test fun pausedAndBufferingSessionsDoNotAdvanceAndUnknownPositionsStayUnknown() {
        assertEquals(30_000, track.copy(playing = false, advancing = false).positionAt(15_000))
        assertEquals(30_000, track.copy(advancing = false).positionAt(15_000))
        assertEquals(30_000, track.copy(updatedAtMs = 0).positionAt(15_000))
        assertEquals(-1, track.copy(positionMs = -1).positionAt(15_000))
        assertEquals(35_000, track.copy(durationMs = 0).positionAt(15_000))
    }
}
