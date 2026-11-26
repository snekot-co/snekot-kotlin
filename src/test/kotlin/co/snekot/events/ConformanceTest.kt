package co.snekot.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The shared cases in conformance.json: every call must put exactly that method, path and body on the wire. */
class ConformanceTest {
    private val suite = Json.parseToJsonElement(javaClass.getResource("/conformance.json")!!.readText()).jsonObject

    @Test
    fun everyCaseMatchesTheWireFormat() {
        val client = SnekotEvents(token = "sk_test", project = suite["project"]!!.jsonPrimitive.content)
        // In the fixture's order.
        val built: List<WireRequest> = listOf(
            client.trackRequest("payments", "New subscription", description = "jane upgraded", icon = ":moneybag:", notify = true, userId = "user-1", tags = mapOf("plan" to "pro", "seats" to 3, "trial" to false), parser = Parser.MARKDOWN),
            client.trackRequest("signups", "User signed up"),
            client.trackRequest("signups", "Backfilled", timestamp = 1700000000),
            client.identifyRequest("user-1", mapOf("name" to "Jane", "plan" to "pro")),
            client.groupRequest("northwind", userId = "user-1", properties = mapOf("name" to "Northwind")),
            client.subscribeRequest("jane@example.com", name = "Jane", properties = mapOf("plan" to "pro", "seats" to 3), source = "/blog/launch", notify = true, language = "sv-SE", timezone = "Europe/Stockholm"),
            client.subscribeRequest("jane@example.com"),
            client.insightTrackRequest("MRR", "\$1,289", icon = ":chart_with_upwards_trend:"),
            client.insightIncrementRequest("Orders", 2),
        )
        val cases = suite["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(cases.size, built.size, "the fixture and this test have the same number of cases")
        built.zip(cases).forEach { (request, case) ->
            val expect = case["expect"] as JsonObject
            val name = case["call"]!!.jsonPrimitive.content
            assertEquals(expect["method"]!!.jsonPrimitive.content, request.method, name)
            assertEquals(expect["path"]!!.jsonPrimitive.content, request.path, name)
            assertEquals(expect["body"], Json.parseToJsonElement(request.body), name)
        }
    }

    private var calls = 0
    private val answers = ArrayDeque<() -> TransportResponse>()
    private val stubbed = SnekotEvents(token = "sk_test", project = "p", transport = { _, _, headers, _ ->
        calls += 1
        assertEquals("Bearer sk_test", headers["Authorization"])
        (answers.removeFirstOrNull() ?: { TransportResponse(200, "{}") })()
    })

    @Test
    fun aRefusalIsNotRetriedAndCarriesTheServersSentence() {
        answers.add { TransportResponse(400, "{\"error\":\"bad \\\"channel\\\"\"}") }
        val refusal = runCatching { stubbed.track("x", "y") }.exceptionOrNull()
        assertIs<RefusedException>(refusal)
        assertEquals(400, refusal.status)
        assertEquals("bad \"channel\"", refusal.message)
        assertEquals(1, calls)
    }

    @Test
    fun aRefusalWithABrokenBodyStillSurfacesAsARefusal() {
        answers.add { TransportResponse(400, "{\"error\":\"bad \\u12") }
        val refusal = runCatching { stubbed.track("x", "y") }.exceptionOrNull()
        assertIs<RefusedException>(refusal)
        assertEquals("The server answered 400.", refusal.message)
    }

    @Test
    fun aServerErrorIsRetriedOnce() {
        answers.add { TransportResponse(503, "{}") }
        assertTrue(runCatching { stubbed.track("x", "y") }.isSuccess)
        assertEquals(2, calls)
    }

    @Test
    fun twoNetworkFailuresSurfaceAsUnreachable() {
        answers.add { throw java.io.IOException("down") }
        answers.add { throw java.io.IOException("down") }
        assertIs<UnreachableException>(runCatching { stubbed.track("x", "y") }.exceptionOrNull())
        assertEquals(2, calls)
    }

    @Test
    fun anExperimentAnswersWithTheServersVariant() {
        val request = stubbed.experimentRequest("pricing", listOf("control", "new"), userId = "user-1")
        assertEquals("/v1/experiment", request.path)
        assertEquals(Json.parseToJsonElement("""{"project":"p","experiment":"pricing","variants":["control","new"],"user_id":"user-1"}"""), Json.parseToJsonElement(request.body))
        answers.add { TransportResponse(200, """{"experiment":"pricing","variant":"new"}""") }
        assertEquals("new", stubbed.experiment("pricing", listOf("control", "new"), userId = "user-1"))
        assertEquals(1, calls)
    }

    @Test
    fun aDisabledExperimentIsItsFirstVariant() {
        stubbed.disabled = true
        assertEquals("control", stubbed.experiment("pricing", listOf("control", "new")))
        assertEquals(0, calls)
    }

    @Test
    fun aFlagAnswersWithWhatTheServerSays() {
        val request = stubbed.flagRequest("new-checkout", userId = "user-1")
        assertEquals("POST", request.method)
        assertEquals("/v1/flag", request.path)
        assertEquals(Json.parseToJsonElement("""{"project":"p","flag":"new-checkout","user_id":"user-1"}"""), Json.parseToJsonElement(request.body))
        assertEquals(
            Json.parseToJsonElement("""{"project":"p","flag":"new-checkout","ip":"203.0.113.7","user_agent":"Mozilla/5.0"}"""),
            Json.parseToJsonElement(stubbed.flagRequest("new-checkout", ip = "203.0.113.7", userAgent = "Mozilla/5.0").body),
        )
        answers.add { TransportResponse(200, """{"flag":"new-checkout","on":true}""") }
        assertEquals(true, stubbed.flag("new-checkout", userId = "user-1"))
        answers.add { TransportResponse(200, """{"flag":"new-checkout","on":false}""") }
        assertEquals(false, stubbed.flag("new-checkout", userId = "user-1"))
        assertEquals(2, calls)
    }

    @Test
    fun aFlagWithNoAnswerInTheBodyIsOff() {
        assertEquals(false, stubbed.flag("new-checkout"))
        assertEquals(1, calls)
    }

    @Test
    fun aFlagTheServerRefusesThrows() {
        answers.add { TransportResponse(403, """{"error":"This key is limited to channels."}""") }
        val refusal = runCatching { stubbed.flag("new-checkout") }.exceptionOrNull()
        assertIs<RefusedException>(refusal)
        assertEquals(403, refusal.status)
        assertEquals("This key is limited to channels.", refusal.message)
        assertEquals(1, calls)
    }

    @Test
    fun aFlagThatGetsNoAnswerThrowsUnreachable() {
        answers.add { throw java.io.IOException("down") }
        answers.add { throw java.io.IOException("down") }
        assertIs<UnreachableException>(runCatching { stubbed.flag("new-checkout") }.exceptionOrNull())
        assertEquals(2, calls)
    }

    @Test
    fun aDisabledFlagIsOff() {
        stubbed.disabled = true
        assertEquals(false, stubbed.flag("new-checkout"))
        assertEquals(0, calls)
    }

    @Test
    fun disabledTrackingSendsNothing() {
        stubbed.disabled = true
        stubbed.track("x", "y")
        assertEquals(0, calls)
    }

    // The offline queue

    private fun folder() = java.nio.file.Files.createTempDirectory("snekot-tests").toFile()
    private fun eventOf(body: String) = Json.parseToJsonElement(body).jsonObject["event"]?.jsonPrimitive?.content
    private val down = Transport { _, _, _, _ -> throw java.io.IOException("down") }

    @Test
    fun whatCannotBeSentIsKeptAndSurvivesARestart() {
        val directory = folder()
        val offline = SnekotEvents(token = "pk_test", project = "p", transport = down, queue = OfflineQueue(directory))
        offline.track("a", "first")
        offline.identify("u", mapOf("plan" to "pro"))
        assertEquals(2, OfflineQueue(directory).count)
    }

    @Test
    fun whatWasKeptGoesOutFirstAndInOrder() {
        val directory = folder()
        val offline = SnekotEvents(token = "sk_test", project = "p", transport = down, queue = OfflineQueue(directory))
        offline.track("a", "first", description = "a tab\tand a line\nbreak")
        offline.track("a", "second")

        val sent = mutableListOf<String>()
        val queue = OfflineQueue(directory)
        val online = SnekotEvents(token = "sk_test", project = "p", transport = { _, _, _, body -> sent.add(body); TransportResponse(200, "{}") }, queue = queue)
        online.track("a", "third")
        assertEquals(listOf("first", "second", "third"), sent.map(::eventOf))
        assertEquals(listOf(true, true, false), sent.map { "timestamp" in Json.parseToJsonElement(it).jsonObject }, "a kept event carries the moment it happened; a live one does not")
        assertEquals("a tab\tand a line\nbreak", Json.parseToJsonElement(sent[0]).jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(0, queue.count)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun withAPublicKeyAKeptEventCarriesItsTimeToo() {
        val directory = folder()
        SnekotEvents(token = "pk_test", project = "p", transport = down, queue = OfflineQueue(directory)).track("a", "first")
        val sent = mutableListOf<String>()
        SnekotEvents(token = "pk_test", project = "p", transport = { _, _, _, body -> sent.add(body); TransportResponse(200, "{}") }, queue = OfflineQueue(directory)).flush()
        assertEquals(listOf(true), sent.map { "timestamp" in Json.parseToJsonElement(it).jsonObject }, "a public key may set a time within the last 7 days")
    }

    @Test
    fun aRefusalIsThrownAndNeverKept() {
        val queue = OfflineQueue(folder())
        val events = SnekotEvents(token = "pk_test", project = "p", transport = { _, _, _, _ -> TransportResponse(400, "{\"error\":\"no\"}") }, queue = queue)
        assertIs<RefusedException>(runCatching { events.track("Not Valid", "x") }.exceptionOrNull())
        assertEquals(0, queue.count)
    }

    @Test
    fun aKeptEventTheServerRefusesIsDroppedAndTheRestGoOut() {
        val queue = OfflineQueue(folder())
        queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"bad\"}"))
        queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"good\"}"))
        val sent = mutableListOf<String?>()
        val events = SnekotEvents(token = "pk_test", project = "p", transport = { _, _, _, body -> sent.add(eventOf(body)); TransportResponse(if (eventOf(body) == "bad") 400 else 200, "{}") }, queue = queue)
        assertEquals(0, events.flush())
        assertEquals(listOf<String?>("bad", "good"), sent)
    }

    @Test
    fun tooManyRequestsKeepsEverythingForLater() {
        val queue = OfflineQueue(folder())
        queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"one\"}"))
        queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"two\"}"))
        val events = SnekotEvents(token = "pk_test", project = "p", transport = { _, _, _, _ -> TransportResponse(429, "{\"error\":\"wait\"}") }, queue = queue)
        assertEquals(2, events.flush())
    }

    @Test
    fun theQueueKeepsTheNewestAndDropsTheOld() {
        val queue = OfflineQueue(folder(), limit = 2, maxAge = java.time.Duration.ofMinutes(1))
        queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"expired\"}"), at = System.currentTimeMillis() - 120_000)
        listOf("one", "two", "three").forEach { queue.add(WireRequest("POST", "/v1/log", "{\"event\":\"$it\"}")) }
        val sent = mutableListOf<String?>()
        SnekotEvents(token = "pk_test", project = "p", transport = { _, _, _, body -> sent.add(eventOf(body)); TransportResponse(200, "{}") }, queue = queue).flush()
        assertEquals(listOf<String?>("two", "three"), sent)
    }
}
