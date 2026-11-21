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
            client.subscribeRequest("jane@example.com", name = "Jane", properties = mapOf("plan" to "pro", "seats" to 3), source = "/blog/launch", notify = true),
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
    fun disabledTrackingSendsNothing() {
        stubbed.disabled = true
        stubbed.track("x", "y")
        assertEquals(0, calls)
    }
}
