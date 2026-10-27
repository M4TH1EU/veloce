package ch.broillet.veloce.connection

import java.io.Closeable
import java.io.IOException

internal class LinkResources : Closeable {
    private val resources = mutableListOf<Closeable>()
    private var closed = false
    val isClosed: Boolean @Synchronized get() = closed

    @Synchronized fun <T : Closeable> own(resource: T): T {
        if (closed) { runCatching { resource.close() }; throw IOException("Connection cancelled") }
        resources.add(resource)
        return resource
    }

    override fun close() {
        val current = synchronized(this) {
            if (closed) return
            closed = true
            resources.reversed().also { resources.clear() }
        }
        current.forEach { runCatching { it.close() } }
    }
}
