# kotlin-dsl-chat

Atmosphere is **Kotlin-first too**. This sample builds a complete chat endpoint
with the Atmosphere **Kotlin DSL** and delivers every message through the
**coroutine extensions** — no Java, no annotations, no API key.

It runs fully offline: a small [`DeterministicAgent`](src/main/kotlin/org/atmosphere/samples/kotlindsl/DeterministicAgent.kt)
computes the replies by rule, so the behavior is reproducible end to end.

## What it demonstrates

| Kotlin feature | Where | API |
|---|---|---|
| DSL endpoint builder | [`KotlinDslChat.chatHandler()`](src/main/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChat.kt) | `atmosphere { onConnect { } ; onMessage { } ; onDisconnect { } }` (`org.atmosphere.kotlin`) |
| Suspending broadcast | every callback | `Broadcaster.broadcastSuspend(message)` — awaits delivery, not fire-and-forget |
| Suspending write | delivery test | `AtmosphereResource.writeSuspend(data)` |

The DSL and coroutine extensions ship in the `atmosphere-kotlin` module
(`org.atmosphere:atmosphere-kotlin`). This sample is the runnable proof that
they assemble and drive a real endpoint.

## The endpoint, in full

```kotlin
fun chatHandler(): AtmosphereHandler = atmosphere {
    onConnect { resource ->
        runBlocking { resource.broadcaster.broadcastSuspend("${resource.uuid()} joined") }
    }
    onMessage { resource, message ->
        val answer = agent.reply(message)            // deterministic, offline
        runBlocking { resource.broadcaster.broadcastSuspend(answer) }
    }
    onDisconnect { resource ->
        runBlocking { resource.broadcaster.broadcastSuspend("${resource.uuid()} left") }
    }
}
```

`broadcastSuspend` is a coroutine extension on `Broadcaster`; it suspends until
`broadcast(message).get()` completes, so the callback only returns once the
message has actually been delivered.

## Run it

```bash
# from the repo root
./mvnw -q -pl samples/kotlin-dsl-chat -am package -DskipTests
java -jar samples/kotlin-dsl-chat/target/atmosphere-kotlin-dsl-chat-*.jar
```

The server listens on `http://localhost:8099/chat` (override with
`-Dserver.port=...`). In one terminal subscribe, in another send a message:

```bash
curl -N http://localhost:8099/chat      # subscribe (streams broadcasts)
curl -d 'ping' http://localhost:8099/chat   # -> "pong"
curl -d 'hello' http://localhost:8099/chat  # -> "echo: hello"
```

## Proof: the delivery test

[`KotlinDslChatDeliveryTest`](src/test/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChatDeliveryTest.kt)
drives a real message through the DSL-built handler and asserts the **observable
behavior**, not that an object exists:

- a `POST "ping"` flows through the DSL endpoint, the agent answers `"pong"`,
  and the `broadcastSuspend` coroutine extension delivers exactly that payload;
- `broadcastSuspend` awaits the broadcast future and surfaces its resolved value;
- `writeSuspend` writes its payload to the resource and returns it for chaining.

```bash
./mvnw -q -pl samples/kotlin-dsl-chat -am test
```

## Make it a real AI agent

Replace `DeterministicAgent` with any Atmosphere `AgentRuntime` /`@AiEndpoint`
(LangChain4j, Spring AI, Anthropic, Cohere, ...). The Kotlin DSL wiring above
does not change — only `agent.reply(...)` becomes a model call.
