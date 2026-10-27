package ch.broillet.veloce.projection

internal fun mapTouch(coordinate: Long, inputSize: Int, videoSize: Int, margin: Int): Float =
    (margin / 2f + coordinate.coerceIn(0, inputSize - 1L).toFloat() * (videoSize - margin) / inputSize)
        .coerceAtMost(videoSize - 1f)
