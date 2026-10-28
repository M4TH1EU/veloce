package ch.broillet.veloce.hosting

import org.junit.Assert.assertEquals
import org.junit.Test

class HostApiTest {
    @Test fun negotiatesCommonLevel() {
        assertEquals(2, HostApi.negotiate(1, 2))
        assertEquals(7, HostApi.negotiate(6, 8))
        assertEquals(7, HostApi.negotiate(7, 7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNewerMinimum() { HostApi.negotiate(8, 9) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedRange() { HostApi.negotiate(4, 2) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMinimum() { HostApi.negotiate(0, 7) }
}
