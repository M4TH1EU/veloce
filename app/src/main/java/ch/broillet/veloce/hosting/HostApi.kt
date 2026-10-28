package ch.broillet.veloce.hosting

internal object HostApi {
    const val MAX_LEVEL = 7

    fun negotiate(minimum: Int, latest: Int): Int {
        require(minimum >= 1 && latest >= minimum) { "Invalid app API range $minimum–$latest" }
        require(minimum <= MAX_LEVEL) { "App requires Car App API $minimum; Veloce supports up to $MAX_LEVEL" }
        return minOf(latest, MAX_LEVEL)
    }
}
