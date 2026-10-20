# kotlin-dsl-chat

Atmosphere is **Kotlin-first too**. This sample builds a complete chat endpoint
with the Atmosphere **Kotlin transport DSL**, answers every message with an
agent declared through the **Kotlin agent DSL**, and delivers the reply with the
**coroutine extensions** — no Java, no annotations, no API key. Its UI is the
**Atmosphere Console**, served by the sample's own Jetty at `/`.

It runs fully offline. The agent resolves its runtime exactly as an
`@Agent`-annotated class does; with no provider configured that is the
framework's built-in `DemoAgentRuntime`, on which the sample installs a
deterministic response strategy — so the behavior is reproducible end to end
while still traversing the complete AI pipeline (memory, guardrails, metrics,
streaming frames).

## What it demonstrates

| Kotlin feature | Where | API |
|---|---|---|
| Transport DSL endpoint builder | [`KotlinDslChat.chatHandler()`](src/main/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChat.kt) | `atmosphere { onConnect { } ; onMessage { } ; onDisconnect { } }` (`org.atmosphere.kotlin`) |
| Agent DSL | [`KotlinDslChat.registerAssistant()`](src/main/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChat.kt) | `framework.registerAgent("kotlin-dsl-chat") { systemPrompt = …; tool(…) { } }` (`org.atmosphere.kotlin.ai`) |
| Suspending agent call | `onMessage` | `KotlinAgent.ask(conversationId, message)` — drives the AI pipeline, suspends until the answer is complete |
| Suspending broadcast | every callback | `Broadcaster.broadcastSuspend(message)` — awaits delivery, not fire-and-forget |
| Suspending write | delivery test | `AtmosphereResource.writeSuspend(data)` |

Both DSLs ship in the `atmosphere-kotlin` module
(`org.atmosphere:atmosphere-kotlin`); the agent DSL additionally needs
`org.atmosphere:atmosphere-ai`, which this sample declares. This sample is the
runnable proof that they assemble and drive a real endpoint and a real agent.

## The agent, in full

```kotlin
val assistant = framework.registerAgent("kotlin-dsl-chat") {
    systemPrompt = "You are the Atmosphere Kotlin DSL demo assistant. …"
    maxHistory = 20

    tool("word_count", "Count the words in a sentence") {
        param("text", "The sentence to measure")
        execute { args -> (args["text"] as? String).orEmpty().split(Regex("\\s+")).count { it.isNotBlank() } }
    }
}
```

Registration goes through the framework's own machinery: the agent lands at
`/atmosphere/agent/kotlin-dsl-chat` on the same `AiEndpointHandler` an
`@Agent`-annotated class produces, its lambda tool lands in the same
`ToolRegistry` that `@AiTool` scanning fills, and `ask` runs the same
`AiPipeline` the A2A / AG-UI / channel surfaces use.

**Note on the tool**: the offline demo runtime does not do tool calling, so
`word_count` is registered but not invoked until you configure a provider whose
runtime supports tools.

## The endpoint, in full

```kotlin
fun chatHandler(assistant: KotlinAgent): AtmosphereHandler = atmosphere {
    onConnect { resource ->
        runBlocking { resource.broadcaster.broadcastSuspend(envelope(SYSTEM_AUTHOR, "${resource.uuid()} joined")) }
    }
    onMessage { resource, message ->
        runBlocking {
            val (author, text) = parseMessage(message)
            resource.broadcaster.broadcastSuspend(envelope(author, text))         // everyone sees it
            val answer = assistant.ask(resource.uuid() ?: "anonymous", text)      // real AI pipeline
            resource.broadcaster.broadcastSuspend(envelope(AGENT_NAME, answer))
        }
    }
    onDisconnect { resource ->
        runBlocking { resource.broadcaster.broadcastSuspend(envelope(SYSTEM_AUTHOR, "${resource.uuid()} left")) }
    }
}
```

Every frame is the Console's broadcast dialect, a JSON
`{"author": …, "message": …, "time": …}` envelope. A message may arrive as that
envelope (the Console sends one) or as plain text (`curl -d 'ping'`, sent as
`anonymous`).

`broadcastSuspend` is a coroutine extension on `Broadcaster`; it suspends until
`broadcast(message).get()` completes, so the callback only returns once the
message has actually been delivered.

## Run it

```bash
# from the repo root
./mvnw -q -pl samples/kotlin-dsl-chat -am package -DskipTests
java -jar samples/kotlin-dsl-chat/target/atmosphere-kotlin-dsl-chat-*.jar
```

Open `http://localhost:8099/` (override the port with `SERVER_PORT` or
`-Dserver.port=...`): it redirects to the Atmosphere Console at
`/atmosphere/console/`, keeping the query string (`/?token=x` opens the Console
with that token). The Console reads `/api/console/info`, connects to `/chat` in
broadcast mode, and renders every message: send `ping` and the answer
`kotlin-dsl-chat: pong` appears; open a second page and each sees the other's
messages. The bundle is the shared Console build, synced into
`src/main/webapp/atmosphere/console` by `scripts/sync-console-bundle.sh`.

From a terminal, subscribe in one and send in another:

```bash
curl -N http://localhost:8099/chat      # subscribe (streams broadcasts)
curl -d 'ping' http://localhost:8099/chat   # -> {"author":"anonymous","message":"ping",…} then {"author":"kotlin-dsl-chat","message":"pong",…}
curl -d 'hello' http://localhost:8099/chat  # -> … {"author":"kotlin-dsl-chat","message":"echo: hello",…}
```

The servlet installs `UUIDBroadcasterCache` (`KotlinDslChat.SERVLET_INIT_PARAMS`),
so a reply broadcast while a long-polling client is between two polls, or while a
stream reconnects, is kept and delivered on that client's next connection.

The agent endpoint is registered too. A prompt POST must name the connection
it belongs to (the `X-Atmosphere-tracking-id` atmosphere.js sends), so the agent
answers a bare `curl` POST `400` instead of running it for every subscriber; an
undeclared agent path is not routed at all:

```bash
curl -i -d 'ping' http://localhost:8099/atmosphere/agent/kotlin-dsl-chat  # 400 (no connection named)
curl -i -d 'ping' http://localhost:8099/atmosphere/agent/not-declared     # 404
```

## Proof: the delivery test

[`KotlinDslChatDeliveryTest`](src/test/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChatDeliveryTest.kt)
registers the agent into a real `AtmosphereFramework` and drives a real message
through the DSL-built handler, asserting the **observable behavior**, not that
an object exists:

- the agent DSL registers an `AiEndpointHandler` at
  `/atmosphere/agent/kotlin-dsl-chat` and its lambda tool executes from the
  agent's tool registry;
- a `POST "ping"` flows through the DSL endpoint, the agent answers `"pong"`
  through the AI pipeline, and the `broadcastSuspend` coroutine extension
  delivers the sender's message and that answer as broadcast envelopes; a
  Console envelope keeps its author;
- the conversation is recorded in the agent's memory;
- `broadcastSuspend` awaits the broadcast future and surfaces its resolved value;
- `writeSuspend` writes its payload to the resource and returns it for chaining.

[`KotlinDslChatConsoleTest`](src/test/kotlin/org/atmosphere/samples/kotlindsl/KotlinDslChatConsoleTest.kt)
starts the sample's Jetty and checks that `/` redirects to the Console with the
query string kept (GET and HEAD), that the bundle is served, and that
`/api/console/info` points it at the broadcast chat on `/chat`.

The tests pin the offline demo runtime explicitly so it never depends on the
developer's API keys or the network.

```bash
./mvnw -q -pl samples/kotlin-dsl-chat -am test
```

## Make it a real AI agent

Add any Atmosphere provider module (`atmosphere-langchain4j`,
`atmosphere-spring-ai`, `atmosphere-anthropic`, `atmosphere-cohere`, …) and
export its key:

```bash
export LLM_API_KEY=...
java -jar samples/kotlin-dsl-chat/target/atmosphere-kotlin-dsl-chat-*.jar
```

`AgentRuntimeResolver` then stops selecting the demo runtime and hands the same
agent to your provider — the system prompt, the memory window, and the
`word_count` tool all apply, and no code in this sample changes. Note that the
deterministic answers above (`ping` → `pong`) come from the offline runtime;
with a provider configured, the model answers instead.
