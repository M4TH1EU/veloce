package ch.broillet.veloce.protocol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VideoOfferTest {
    @Test fun `configuration indices survive unknown modes and packed fields`() {
        val discovery = Proto(Proto.build {
            bytes(1, Proto.build {
                number(1, 21)
                bytes(3, Proto.build {
                    number(1, 3)
                    bytes(4, Proto.build { number(1, 99) })
                    bytes(4, Proto.build { number(1, 2); number(5, 0); number(3, 40); number(4, 80) })
                })
            })
        })
        val offer = VideoOffer(discovery)
        val config = Proto(Proto.build { bytes(3, byteArrayOf(0, 1)) })
        val selected = offer.choose(config.integers(3))
        assertEquals(21, offer.channel)
        assertEquals(1, selected.index)
        assertEquals(1280, selected.width)
        assertEquals(160, selected.density)
        assertEquals(40, selected.cropX)
        assertThrows(IOException::class.java) { offer.choose(listOf(0)) }
    }

    @Test fun `main display input uses advertised coordinates and only implemented keys`() {
        val offer = VideoOffer(Proto(Proto.build {
            bytes(1, Proto.build { number(1, 2); bytes(4, Proto.build { number(5, 1) }) })
            bytes(1, Proto.build { number(1, 3); bytes(4, Proto.build {
                listOf(3, 4, 19, 23, 85, 87, 88, 126, 127, 84, 219, 231, 86, 65536, 65541).forEach { number(1, it.toLong()) }
                bytes(2, Proto.build { number(1, 800); number(2, 480) })
            }) })
            bytes(1, Proto.build { number(1, 7); bytes(3, Proto.build {
                number(1, 3)
                bytes(4, Proto.build { number(1, 3); number(3, 160); number(4, 600) })
                bytes(4, Proto.build { number(1, 1); number(3, 800) })
            }) })
        }))
        assertEquals(3, offer.inputChannel)
        assertEquals(listOf(3, 4, 19, 23, 85, 87, 88, 126, 127, 84, 219, 231, 65536, 65541), offer.keys)
        val mode = offer.choose(listOf(0, 1))
        assertEquals(800, mode.touchWidth)
        assertEquals(480, mode.touchHeight)
        assertEquals(600, mode.cropY)
        assertEquals(1, offer.modes.size)
    }
}
