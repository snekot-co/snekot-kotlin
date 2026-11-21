# snekot-events (Kotlin)

The [Snekot](https://snekot.co) SDK for Kotlin and the JVM. Post product events, get them as push notifications, identify users and set insights.

Java 11 or newer. No dependencies beyond the Kotlin standard library.

```kotlin
dependencies {
    implementation("co.snekot:snekot-events:0.1.0")
}
```

## Use

```kotlin
import co.snekot.events.SnekotEvents

val events = SnekotEvents(token = token, project = "acme-store")

events.track(channel = "payments", event = "New subscription", icon = "💰", notify = true, tags = mapOf("plan" to "pro"))
events.identify("user-123", mapOf("plan" to "pro"))
events.group("acme", userId = "user-123", properties = mapOf("name" to "Acme"))
events.subscribe("jane@example.com", name = "Jane Park")
events.insight.track("MRR", "$1,289")
events.insight.increment("Orders")
```

Use a private key (`sk_…`) on a server, and a **public** key (`pk_…`) in an app that ships to other people's devices. See [Keys](https://snekot.co/docs/keys).

Calls block until the server answers, so run them off the main thread. From a coroutine: `withContext(Dispatchers.IO) { events.track(…) }`.

### Android

The default transport is the JDK's `java.net.http` client, which Android does not ship. Pass your own `Transport`; it is one function, and a few lines over OkHttp:

```kotlin
val http = OkHttpClient()
val events = SnekotEvents(token = token, project = "acme-store", transport = { method, url, headers, body ->
    val request = Request.Builder().url(url).method(method, body.toRequestBody("application/json".toMediaType()))
    headers.forEach { (name, value) -> request.header(name, value) }
    http.newCall(request.build()).execute().use { TransportResponse(it.code, it.body?.string().orEmpty()) }
})
```

### Behaviour

- One attempt may take 10 seconds (`timeout`).
- A network failure or a server error is retried once, after a quarter of a second, then throws `UnreachableException`.
- A refusal (a 4xx, such as a bad channel name) is never retried. It throws `RefusedException` with the status and the server's own sentence for what to fix.
- `disabled = true` makes every call succeed without sending anything, for tests and previews.
- `endpoint` defaults to `https://api.snekot.co`.

## Develop

```bash
./gradlew build
```

That compiles for Java 11 and runs the cases in `src/test/resources/conformance.json`, the wire contract every Snekot SDK is tested against. It needs a JDK 11 or newer.

## Licence

MIT
