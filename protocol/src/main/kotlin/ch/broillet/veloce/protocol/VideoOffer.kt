package ch.broillet.veloce.protocol

import java.io.IOException

data class VideoMode(
    val index: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val density: Int,
    val cropX: Int,
    val cropY: Int,
    val touchWidth: Int = width - cropX,
    val touchHeight: Int = height - cropY,
)

class VideoOffer(discovery: Proto) {
    private val services = discovery.messages(1)
    private val video = services.firstOrNull { service ->
        val sink = service.bytes(3)?.let(::Proto)
        sink != null && sink.number(1) == 3L && sink.messages(4).isNotEmpty() && sink.number(6) == 0L && sink.number(7) == 0L
    } ?: throw IOException("The car did not offer a main H.264 display")
    val channel = video.number(1).toInt().also { if (it !in 1..255) throw IOException("Invalid video channel") }
    private val input = services.firstOrNull { it.bytes(4)?.let(::Proto)?.number(5) == 0L }
    val inputChannel = input?.number(1)?.toInt()?.takeIf { it in 1..255 }
        .also { if (it == channel) throw IOException("Video and input share a channel ID") }
    private val inputService = input?.bytes(4)?.let(::Proto)
    val keys = inputService?.integers(1).orEmpty().filter { it in SUPPORTED_KEYS }.distinct()
    private val touchscreen = inputService?.messages(2)?.firstOrNull { it.number(4) == 0L }
    val modes = Proto(checkNotNull(video.bytes(3))).messages(4).mapIndexedNotNull { index, config ->
        val dimensions = when (config.number(1).toInt()) {
            1 -> 800 to 480
            2 -> 1280 to 720
            3 -> 1920 to 1080
            6 -> 720 to 1280
            7 -> 1080 to 1920
            else -> return@mapIndexedNotNull null
        }
        if (config.number(10, 3) != 3L) return@mapIndexedNotNull null
        val cropX = config.number(3)
        val cropY = config.number(4)
        if (cropX !in 0L until dimensions.first || cropY !in 0L until dimensions.second) return@mapIndexedNotNull null
        VideoMode(index, dimensions.first, dimensions.second, if (config.number(2) == 1L) 60 else 30,
            config.number(5, 160).toInt().let { if (it == 0) 160 else it.coerceIn(80, 640) },
            cropX.toInt(), cropY.toInt(),
            touchscreen?.number(1)?.takeIf { it in 1..65535 }?.toInt() ?: (dimensions.first - cropX.toInt()),
            touchscreen?.number(2)?.takeIf { it in 1..65535 }?.toInt() ?: (dimensions.second - cropY.toInt()))
    }

    fun choose(indices: List<Int>): VideoMode = modes.filter { it.index in indices }
        .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) }
        ?: throw IOException("The car offered no supported H.264 video configuration")

    companion object {
        val SUPPORTED_KEYS = setOf(3, 4, 19, 20, 21, 22, 23, 66, 84, 85, 87, 88, 126, 127, 219, 231, 65536, 65541)
    }
}
