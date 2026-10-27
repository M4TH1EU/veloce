package ch.broillet.veloce.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class AckWindowTest {
    @Test fun `video waits for credit and cancellation releases blocked producers`() {
        val window = AckWindow()
        val executor = Executors.newSingleThreadExecutor()
        try {
            window.acquire(0)
            val waiting = CountDownLatch(1)
            val next = executor.submit { waiting.countDown(); window.acquire(0) }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { next.get(50, TimeUnit.MILLISECONDS) }
            window.acknowledge(0, 1)
            next.get(1, TimeUnit.SECONDS)
            val cancelled = executor.submit<Boolean> { window.acquire(0) }
            window.close()
            assertFalse(cancelled.get(1, TimeUnit.SECONDS))
        } finally { window.close(); executor.shutdownNow() }
    }

    @Test fun `focus changes cancel old producers and stale acknowledgements cannot credit a new stream`() {
        val window = AckWindow()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(window.acquire(0))
            val old = executor.submit<Boolean> { window.acquire(0) }
            window.reset(1)
            assertFalse(old.get(1, TimeUnit.SECONDS))
            assertTrue(window.acquire(1))
            val next = executor.submit<Boolean> { window.acquire(1) }
            window.acknowledge(0, 8)
            window.acknowledge(1, 0)
            assertThrows(TimeoutException::class.java) { next.get(50, TimeUnit.MILLISECONDS) }
            window.acknowledge(1, 1)
            assertTrue(next.get(1, TimeUnit.SECONDS))
        } finally { window.close(); executor.shutdownNow() }
    }
}
