package co.snekot.events

import java.io.File
import java.time.Duration

/**
 * Keeps events that could not be sent, on the device, and sends them later in the order they happened.
 *
 * Give one to [SnekotEvents]. A call that gets no answer, because the device is offline or the server is down,
 * is then kept instead of thrown, and goes out before the next call that does get through, or when you call
 * [SnekotEvents.flush]. What the server refuses is never kept: it would be refused again.
 *
 * What is kept is the request's body, in a file in [directory]. The key is not written to it. On Android,
 * pass `context.filesDir`. Events older than [maxAge] are dropped, and when there are more than [limit], the
 * oldest are. Safe to share between threads.
 *
 * @param name the file's name, for an app that keeps more than one queue
 */
class OfflineQueue @JvmOverloads constructor(
    directory: File,
    private val limit: Int = 500,
    private val maxAge: Duration = Duration.ofDays(7),
    name: String = "snekot-queue",
) {
    private class Stored(val at: Long, val request: WireRequest)

    private val file = File(directory.apply { mkdirs() }, "$name.tsv")
    private val items = ArrayDeque<Stored>()
    private var draining = false

    init {
        // One request a line: when it was kept, the method, the path and the body. A JSON body written by this
        // SDK holds no tab or line break. A line that cannot be read is skipped, never a crash.
        runCatching {
            if (file.exists()) file.readLines().forEach { line ->
                val parts = line.split('\t', limit = 4)
                val at = parts.getOrNull(0)?.toLongOrNull()
                if (parts.size == 4 && at != null) items.add(Stored(at, WireRequest(parts[1], parts[2], parts[3])))
            }
        }
        prune()
    }

    /** How many events are waiting. */
    val count: Int
        @Synchronized get() {
            prune()
            return items.size
        }

    /** Forgets everything that is waiting, for when a person signs out or withdraws consent. */
    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    @Synchronized
    internal fun add(request: WireRequest, at: Long = System.currentTimeMillis()) {
        items.add(Stored(at, request))
        prune()
        save()
    }

    /**
     * Sends what is waiting, oldest first. True when nothing is left; false when it stopped because a request got
     * no answer, or because another thread is already sending.
     */
    internal fun drain(send: (WireRequest) -> Unit): Boolean {
        synchronized(this) {
            prune()
            if (items.isEmpty()) return true
            if (draining) return false
            draining = true
        }
        try {
            while (true) {
                // The lock is not held while a request is on its way, so other threads can keep adding.
                val next = synchronized(this) { items.firstOrNull() } ?: return true
                try {
                    send(next.request)
                } catch (refused: RefusedException) {
                    // Too many requests for now passes, so everything stays for the next try.
                    if (refused.status == 429) return false
                    // Anything else refused once is refused always; keeping it would hold up everything behind it.
                } catch (unreachable: UnreachableException) {
                    return false
                }
                synchronized(this) {
                    items.remove(next)
                    save()
                }
            }
        } finally {
            synchronized(this) { draining = false }
        }
    }

    private fun prune() {
        val oldest = System.currentTimeMillis() - maxAge.toMillis()
        items.removeAll { it.at < oldest }
        while (items.size > maxOf(1, limit)) items.removeFirst()
    }

    private fun save() {
        runCatching {
            if (items.isEmpty()) {
                file.delete()
            } else {
                val next = File(file.parentFile, file.name + ".next")
                next.writeText(items.joinToString("\n") { "${it.at}\t${it.request.method}\t${it.request.path}\t${it.request.body}" })
                if (!next.renameTo(file)) { file.delete(); next.renameTo(file) }
            }
        }
    }
}
