/**
 * Post product events, identify users and set insights. One file, no dependencies.
 *
 *     val events = SnekotEvents(token = "sk_...", project = "acme-store")
 *     events.track(channel = "payments", event = "New subscription", icon = ":moneybag:", notify = true, tags = mapOf("plan" to "pro"))
 *     events.insight.increment("Orders", 1)
 *
 * Calls block until the server answers, so call them off the main thread: from a coroutine,
 * `withContext(Dispatchers.IO) { events.track(...) }`.
 *
 * The default transport is the JDK's `java.net.http` client (Java 11 and up). Android does not ship it,
 * so an Android app passes its own [Transport], which is a few lines over OkHttp.
 */
package co.snekot.events

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.TreeMap

/** The server understood the request and said no. [message] is its own sentence for what to fix. Never retried. */
class RefusedException(val status: Int, message: String) : RuntimeException(message)

/** The request got no usable answer twice in a row: the network failed, it timed out, or the server errored. */
class UnreachableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What came back: the status and the body as text. */
class TransportResponse(val status: Int, val body: String)

/** Sends one request. Throw [java.io.IOException] when the network fails; the client retries once. */
fun interface Transport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String): TransportResponse
}

/** A request as it goes on the wire, before the key is attached. The body is JSON with its keys in order. */
data class WireRequest(val method: String, val path: String, val body: String)

enum class Parser { MARKDOWN, TEXT }

class SnekotEvents @JvmOverloads constructor(
    private val token: String,
    val project: String,
    endpoint: String = DEFAULT_ENDPOINT,
    /** How long one attempt may take. */
    private val timeout: Duration = Duration.ofSeconds(10),
    /** Makes every call succeed without sending anything, for tests and previews. */
    @Volatile var disabled: Boolean = false,
    transport: Transport? = null,
    /** Keeps what could not be sent and sends it later. With one, a call that gets no answer no longer throws. */
    private val queue: OfflineQueue? = null,
) {
    private val endpoint = endpoint.trimEnd('/')
    private val transport: Transport = transport ?: JdkTransport(timeout)

    val insight = Insight()

    /**
     * Posts an event to a channel.
     * @param icon one emoji, or a shortcode such as `:moneybag:`
     * @param tags values are strings, numbers or booleans
     * @param timestamp unix seconds; a past moment makes this a backfill, stored at that time and never pushed to a phone
     */
    @JvmOverloads
    fun track(
        channel: String,
        event: String,
        description: String? = null,
        userId: String? = null,
        icon: String? = null,
        notify: Boolean? = null,
        tags: Map<String, Any> = emptyMap(),
        parser: Parser? = null,
        timestamp: Long? = null,
    ) = send(
        trackRequest(channel, event, description, userId, icon, notify, tags, parser, timestamp),
        // An event that has to wait is kept with the moment it happened, so it lands in the feed at the right time.
        keeping = if (queue != null && timestamp == null) trackRequest(channel, event, description, userId, icon, notify, tags, parser, System.currentTimeMillis() / 1000) else null,
    )

    /**
     * Sends what the queue is holding. Call it when the app starts or comes back online; every other call does it
     * too, before sending its own. It answers with how many events are still waiting, and is 0 without a queue.
     */
    fun flush(): Int {
        val queue = queue ?: return 0
        if (disabled) return 0
        queue.drain { request(it) }
        return queue.count
    }

    /** Sets properties on a user; keys sent again replace their old values. */
    fun identify(userId: String, properties: Map<String, Any>) = send(identifyRequest(userId, properties))

    /** Describes a group such as a company, and puts [userId] in it when given. */
    @JvmOverloads
    fun group(groupId: String, userId: String? = null, properties: Map<String, Any> = emptyMap()) = send(groupRequest(groupId, userId, properties))

    /**
     * Puts an address on the project's newsletter list. Signing it up again changes nothing.
     * @param source the page or screen the signup came from, such as `/blog/launch`
     * @param notify push the signup to the phones of everyone on the project
     */
    @JvmOverloads
    fun subscribe(email: String, name: String? = null, properties: Map<String, Any> = emptyMap(), source: String? = null, notify: Boolean? = null) =
        send(subscribeRequest(email, name, properties, source, notify))

    /**
     * Which variant of an A/B test a person gets. The same person always gets the same one.
     * With [disabled] set it is the first variant and nothing is sent.
     * @param variants two to eight names; the first is the one the others are measured against, so keep the order the same everywhere
     * @param userId the person, when they are known
     * @param ip for someone with no id, asked from a server: their address, which is not stored
     * @param userAgent sent along with [ip]
     */
    @JvmOverloads
    fun experiment(name: String, variants: List<String>, userId: String? = null, ip: String? = null, userAgent: String? = null): String {
        require(variants.isNotEmpty()) { "An experiment needs its variants." }
        if (disabled) return variants.first()
        return Json.stringField(request(experimentRequest(name, variants, userId, ip, userAgent)), "variant") ?: variants.first()
    }

    inner class Insight {
        /** Sets an insight to a value: a string or a number. */
        @JvmOverloads
        fun track(title: String, value: Any, icon: String? = null) = send(insightTrackRequest(title, value, icon))

        /** Adds to an insight's number, starting from 0 if it has none. Negative subtracts. */
        @JvmOverloads
        fun increment(title: String, value: Number = 1, icon: String? = null) = send(insightIncrementRequest(title, value, icon))
    }

    // The requests are built apart from sending them, so the conformance test can check the wire format without a server.

    fun trackRequest(channel: String, event: String, description: String? = null, userId: String? = null, icon: String? = null, notify: Boolean? = null, tags: Map<String, Any> = emptyMap(), parser: Parser? = null, timestamp: Long? = null) =
        request("POST", "/v1/log", mapOf("channel" to channel, "event" to event, "description" to description, "user_id" to userId, "icon" to icon, "notify" to notify, "tags" to tags.ifEmpty { null }, "parser" to parser?.name?.lowercase(), "timestamp" to timestamp))

    fun identifyRequest(userId: String, properties: Map<String, Any>) = request("POST", "/v1/identify", mapOf("user_id" to userId, "properties" to properties))

    fun groupRequest(groupId: String, userId: String? = null, properties: Map<String, Any> = emptyMap()) =
        request("POST", "/v1/group", mapOf("group_id" to groupId, "user_id" to userId, "properties" to properties.ifEmpty { null }))

    fun subscribeRequest(email: String, name: String? = null, properties: Map<String, Any> = emptyMap(), source: String? = null, notify: Boolean? = null) =
        request("POST", "/v1/subscribe", mapOf("email" to email, "name" to name, "properties" to properties.ifEmpty { null }, "source" to source, "notify" to notify))

    fun insightTrackRequest(title: String, value: Any, icon: String? = null) = request("POST", "/v1/insight", mapOf("title" to title, "value" to value, "icon" to icon))

    fun insightIncrementRequest(title: String, value: Number, icon: String? = null) = request("PATCH", "/v1/insight", mapOf("title" to title, "value" to mapOf("\$inc" to value), "icon" to icon))

    fun experimentRequest(name: String, variants: List<String>, userId: String? = null, ip: String? = null, userAgent: String? = null) =
        request("POST", "/v1/experiment", mapOf("experiment" to name, "variants" to variants, "user_id" to userId, "ip" to ip, "user_agent" to userAgent))

    private fun request(method: String, path: String, fields: Map<String, Any?>) = WireRequest(method, path, Json.encode(fields + ("project" to project)))

    private fun send(request: WireRequest, keeping: WireRequest? = null) {
        if (disabled) return
        val queue = queue
        if (queue == null) {
            request(request)
            return
        }
        // What is already waiting goes first. If it cannot, this one waits behind it, so nothing arrives out of order.
        if (!queue.drain { request(it) }) {
            queue.add(keeping ?: request)
            return
        }
        try {
            request(request)
        } catch (unreachable: UnreachableException) {
            queue.add(keeping ?: request)
        }
    }

    /** Sends one request, with one retry, and gives back the body the server answered with. */
    private fun request(request: WireRequest): String {
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token")
        // One retry, and only where trying again can help: the network failed or the server did. A refusal is final.
        var retried = false
        while (true) {
            val failure: UnreachableException = try {
                val response = transport.send(request.method, endpoint + request.path, headers, request.body)
                when {
                    response.status in 200..299 -> return response.body
                    response.status >= 500 -> UnreachableException("The server answered ${response.status}.")
                    else -> throw RefusedException(response.status, Json.stringField(response.body, "error") ?: "The server answered ${response.status}.")
                }
            } catch (error: java.io.IOException) {
                UnreachableException("The server at $endpoint could not be reached.", error)
            }
            if (retried) throw failure
            retried = true
            Thread.sleep(250)
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.snekot.co"
    }
}

private class JdkTransport(private val timeout: Duration) : Transport {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun send(method: String, url: String, headers: Map<String, String>, body: String): TransportResponse {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).method(method, HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return TransportResponse(response.statusCode(), response.body())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.IOException("interrupted", error)
        }
    }
}

/** Just enough JSON for these bodies: objects with keys in order, strings, numbers, booleans. Null fields are left out. */
internal object Json {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        // JSON has no NaN or infinity; they go as null, which the server answers with a sentence instead of a parse error.
        is Number -> value.toDouble().let { if (it.isNaN() || it.isInfinite()) "null" else if (it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        is Map<*, *> -> TreeMap(value.filterValues { it != null }.mapKeys { it.key.toString() }).entries.joinToString(",", "{", "}") { quote(it.key) + ":" + encode(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (char in text) when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
        append('"')
    }

    /** The value of a top-level string field in a JSON object, such as the `error` of a refusal. Null if the text is not what was expected. */
    fun stringField(json: String, name: String): String? = runCatching { readStringField(json, name) }.getOrNull()

    private fun readStringField(json: String, name: String): String? {
        val start = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"").find(json)?.range?.last?.plus(1) ?: return null
        val out = StringBuilder()
        var index = start
        while (index < json.length) {
            val char = json[index]
            if (char == '"') return out.toString()
            if (char == '\\' && index + 1 < json.length) {
                index += 1
                when (val escaped = json[index]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> { out.append(json.substring(index + 1, index + 5).toInt(16).toChar()); index += 4 }
                    else -> out.append(escaped)
                }
            } else out.append(char)
            index += 1
        }
        return null
    }
}
