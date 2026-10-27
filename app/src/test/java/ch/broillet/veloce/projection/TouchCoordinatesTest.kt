package ch.broillet.veloce.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinatesTest {
    @Test fun `touchscreen coordinates scale into the visible video rectangle`() {
        assertEquals(80f, mapTouch(0, 800, 1920, 160), 0.01f)
        assertEquals(960f, mapTouch(400, 800, 1920, 160), 0.01f)
        assertEquals(1837.8f, mapTouch(799, 800, 1920, 160), 0.01f)
        assertEquals(80f, mapTouch(-1, 800, 1920, 160), 0.01f)
        assertEquals(1837.8f, mapTouch(Long.MAX_VALUE, 800, 1920, 160), 0.01f)
        assertEquals(719f, mapTouch(719, 720, 720, 0), 0.01f)
    }
}
