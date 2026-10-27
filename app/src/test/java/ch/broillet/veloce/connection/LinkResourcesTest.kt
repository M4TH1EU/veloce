package ch.broillet.veloce.connection

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LinkResourcesTest {
    @Test fun `cancellation closes owned and late resources exactly once despite close failure`() {
        val resources = LinkResources()
        val closed = mutableListOf<Int>()
        resources.own(Closeable { closed.add(1) })
        resources.own(Closeable { closed.add(2); throw IOException("close failed") })
        resources.close()
        resources.close()
        assertThrows(IOException::class.java) { resources.own(Closeable { closed.add(3) }) }
        assertEquals(listOf(2, 1, 3), closed)
        assertTrue(resources.isClosed)
    }
}
