<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

# Atmosphere

The real-time transport layer for Java. Stream AI/LLM tokens, chat messages, and live data to browsers over WebSocket, SSE, and Long-Polling — with built-in rooms, presence, clustering, and MCP support. Runs on Spring Boot, Quarkus, or any Servlet 6.0+ container.

[![Maven Central](https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime)
[![npm](https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue)](https://www.npmjs.com/package/atmosphere.js)
[![Atmosphere CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml)
[![Atmosphere.js CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml)

## AI/LLM Token Streaming

Frameworks like Spring AI, LangChain4j, and Embabel handle **LLM ↔ server** communication. Atmosphere handles the other half: **server ↔ browser**. It streams tokens to the client in real time over WebSocket (with SSE/Long-Polling fallback), manages reconnection and backpressure, and provides React/Vue/Svelte hooks — so you don't have to build all of that yourself.

### What you get

- **`@AiEndpoint` + `@Prompt`** — annotate a class, receive prompts, stream tokens. Runs on virtual threads.
- **Built-in LLM client** — zero-dependency `OpenAiCompatibleClient` that talks to OpenAI, Gemini, Ollama, or any OpenAI-compatible API. No Spring AI or LangChain4j required.
- **Adapter SPI** — plug in Spring AI (`Flux<ChatResponse>`), LangChain4j (`StreamingChatResponseHandler`), or Embabel (`OutputChannel`). All converge on the same `StreamingSession` interface.
- **Standardized wire protocol** — every token is a JSON frame with `type`, `data`, `sessionId`, and `seq` for ordering. Progress events, metadata (model, token usage), and error frames are built in.
- **AI as a room participant** — `LlmRoomMember` joins a Room like any user. When someone sends a message, the LLM receives it, streams a response, and broadcasts it back. Humans and AI in the same room.
- **Client hooks** — `useStreaming()` for React/Vue/Svelte gives you `fullText`, `isStreaming`, `progress`, `metadata`, and `error` out of the box.

### Server — 5 lines with the built-in client

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        AiConfig.get().client().streamChatCompletion(
            ChatCompletionRequest.builder(AiConfig.get().model())
                .user(message).build(),
            session);
    }
}
```

Configure with environment variables — no code changes to switch providers:

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud) or `local` (Ollama) | `remote` |
| `LLM_MODEL` | `gemini-2.5-flash`, `gpt-4o`, `o3-mini`, `llama3.2`, … | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key (or `GEMINI_API_KEY` for Gemini) | — |
| `LLM_BASE_URL` | Override endpoint (auto-detected from model name) | auto |

### Server — with Spring AI, LangChain4j, or Embabel

Atmosphere doesn't replace your AI framework. It gives it a transport:

<details>
<summary>Spring AI adapter</summary>

```java
@Message
public void onMessage(String prompt) {
    StreamingSession session = StreamingSessions.start(resource);
    springAiAdapter.stream(chatClient, prompt, session);
    // Spring AI's Flux<ChatResponse> → session.send(token) → WebSocket frame
}
```

</details>

<details>
<summary>LangChain4j adapter</summary>

```java
@Message
public void onMessage(String prompt) {
    StreamingSession session = StreamingSessions.start(resource);
    model.chat(ChatMessage.userMessage(prompt),
        new AtmosphereStreamingResponseHandler(session));
    // LangChain4j callbacks → session.send(token) → WebSocket frame
}
```

</details>

<details>
<summary>Embabel adapter (Kotlin)</summary>

```kotlin
@Message
fun onMessage(prompt: String) {
    val session = StreamingSessions.start(resource)
    embabelAdapter.stream(AgentRequest("assistant") { channel ->
        agentPlatform.run(prompt, channel)
    }, session)
    // Embabel agent events → session.send(token) / session.progress() → WebSocket frame
}
```

</details>

### Browser — React

```tsx
import { useStreaming } from 'atmosphere.js/react';

function AiChat() {
  const { fullText, isStreaming, progress, send } = useStreaming({
    request: { url: '/ai/chat', transport: 'websocket' },
  });

  return (
    <div>
      <button onClick={() => send('Explain WebSockets')} disabled={isStreaming}>
        Ask
      </button>
      {progress && <p className="muted">{progress}</p>}
      <p>{fullText}</p>
    </div>
  );
}
```

### AI in rooms — virtual members

```java
var client = AiConfig.get().client();
var assistant = new LlmRoomMember("assistant", client, "gpt-4o",
    "You are a helpful coding assistant");

Room room = rooms.room("dev-chat");
room.joinVirtual(assistant);
// Now when any user sends a message, the LLM responds in the same room
```

See the [AI / LLM Streaming wiki](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming) for the full guide.

## Installation

### Maven

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>4.0.1</version>
</dependency>
```

For Spring Boot:
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.1</version>
</dependency>
```

For Quarkus:
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.1</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.atmosphere:atmosphere-runtime:4.0.1'
// or
implementation 'org.atmosphere:atmosphere-spring-boot-starter:4.0.1'
// or
implementation 'org.atmosphere:atmosphere-quarkus-extension:4.0.1'
```

### npm (TypeScript/JavaScript client)

```bash
npm install atmosphere.js
```

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| Core runtime | `atmosphere-runtime` | WebSocket, SSE, Long-Polling transport layer (Servlet 6.0+) |
| Spring Boot starter | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0.2+ |
| Quarkus extension | `atmosphere-quarkus-extension` | Build-time processing for Quarkus 3.21+ |
| AI streaming | `atmosphere-ai` | Token-by-token LLM response streaming |
| Spring AI adapter | `atmosphere-spring-ai` | Spring AI `ChatClient` integration |
| LangChain4j adapter | `atmosphere-langchain4j` | LangChain4j streaming integration |
| MCP server | `atmosphere-mcp` | Model Context Protocol server over WebSocket |
| Rooms | built into `atmosphere-runtime` | Room management with join/leave and presence |
| Redis clustering | `atmosphere-redis` | Cross-node broadcasting via Redis pub/sub |
| Kafka clustering | `atmosphere-kafka` | Cross-node broadcasting via Kafka |
| Durable sessions | `atmosphere-durable-sessions` | Session persistence across restarts (SQLite / Redis) |
| Kotlin DSL | `atmosphere-kotlin` | Builder API and coroutine extensions |
| TypeScript client | `atmosphere.js` (npm) | Browser client with React, Vue, and Svelte bindings |

## Usage

### AI streaming endpoint

Stream LLM responses token-by-token to the browser:

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        myLlmClient.stream(message)
            .forEach(token -> session.send(token));
        session.complete();
    }
}
```

### Chat handler (rooms path)

A handler that broadcasts messages to all connected clients. Works on Spring Boot, Quarkus, or bare Servlet:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject private BroadcasterFactory factory;
    @Inject private AtmosphereResource r;

    @Ready
    public void onReady() {
        // client connected
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public Message onMessage(Message message) {
        return message; // broadcast to all
    }
}
```

### MCP server (MCP path)

Expose tools, resources, and prompt templates to MCP clients:

```java
@McpServer(name = "my-server", path = "/atmosphere/mcp")
public class MyMcpServer {

    @McpTool(name = "get_time", description = "Get the current server time")
    public String getTime(@McpParam(name = "timezone", description = "IANA timezone") String tz) {
        return Instant.now().atZone(ZoneId.of(tz)).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    @McpResource(uri = "atmosphere://server/status", name = "Status",
                  description = "Server status", mimeType = "application/json")
    public String status() {
        return Map.of("status", "running").toString();
    }
}
```

### Browser client

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe({
    url: '/chat',
    transport: 'websocket',       // falls back to SSE / long-polling
    reconnect: true,
}, {
    open:    ()    => console.log('Connected'),
    message: (res) => console.log('Received:', res.responseBody),
    close:   ()    => console.log('Disconnected'),
});

subscription.push(JSON.stringify({ author: 'me', text: 'Hello' }));
```

## Rooms & Presence

Server-side room management with presence tracking:

```java
RoomManager rooms = RoomManager.getOrCreate(framework);
Room lobby = rooms.room("lobby");
lobby.enableHistory(100); // replay last 100 messages to new joiners

lobby.join(resource, new RoomMember("user-1", Map.of("name", "Alice")));
lobby.broadcast("Hello everyone!");
lobby.onPresence(event -> log.info("{} {} room '{}'",
    event.member().id(), event.type(), event.room().name()));
```

## Framework Integration

### Spring Boot

The starter provides auto-configuration for Spring Boot 4.0.2+.

```yaml
atmosphere:
  packages: com.example.chat
```

<details>
<summary>Configuration properties</summary>

| Property | Default | Description |
|----------|---------|-------------|
| `servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `packages` | | Annotation scanning packages |
| `order` | `0` | Servlet load-on-startup order |
| `session-support` | `false` | Enable HttpSession support |
| `websocket-support` | | Enable/disable WebSocket |
| `heartbeat-interval-in-seconds` | | Server heartbeat frequency |
| `broadcaster-class` | | Custom Broadcaster FQCN |
| `broadcaster-cache-class` | | Custom BroadcasterCache FQCN |
| `init-params` | | Map of any `ApplicationConfig` key/value |

</details>

<details>
<summary>GraalVM native image</summary>

The starter includes AOT runtime hints. Activate the `native` Maven profile:

```bash
./mvnw -Pnative package -pl samples/spring-boot-chat
./samples/spring-boot-chat/target/atmosphere-spring-boot-chat
```

Requires GraalVM JDK 25+ (Spring Boot 4.0 / Spring Framework 7 baseline).

</details>

### Quarkus

The extension provides build-time annotation scanning for Quarkus 3.21+.

```properties
quarkus.atmosphere.packages=com.example.chat
```

<details>
<summary>Configuration properties</summary>

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `quarkus.atmosphere.packages` | | Annotation scanning packages |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load-on-startup order |
| `quarkus.atmosphere.session-support` | `false` | Enable HttpSession support |
| `quarkus.atmosphere.broadcaster-class` | | Custom Broadcaster FQCN |
| `quarkus.atmosphere.broadcaster-cache-class` | | Custom BroadcasterCache FQCN |
| `quarkus.atmosphere.heartbeat-interval-in-seconds` | | Server heartbeat frequency |
| `quarkus.atmosphere.init-params` | | Map of any `ApplicationConfig` key/value |

</details>

<details>
<summary>GraalVM native image</summary>

```bash
./mvnw -Pnative package -pl samples/quarkus-chat
./samples/quarkus-chat/target/atmosphere-quarkus-chat-*-runner
```

Requires GraalVM JDK 21+ or Mandrel. Use `-Dquarkus.native.container-build=true` to build without a local GraalVM installation.

</details>

## Framework client bindings

atmosphere.js includes bindings for React, Vue, and Svelte:

<details>
<summary>React</summary>

```tsx
import { AtmosphereProvider, useAtmosphere, useRoom, usePresence } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider>
      <Chat />
    </AtmosphereProvider>
  );
}

function Chat() {
  const { state, data, push } = useAtmosphere<Message>({
    request: { url: '/chat', transport: 'websocket' },
  });

  return state === 'connected'
    ? <button onClick={() => push({ text: 'Hello' })}>Send</button>
    : <p>Connecting…</p>;
}

function ChatRoom() {
  const { joined, members, messages, broadcast } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  return (
    <div>
      <p>{members.length} online</p>
      {messages.map((m, i) => <div key={i}>{m.member.id}: {m.data.text}</div>)}
      <button onClick={() => broadcast({ text: 'Hi' })}>Send</button>
    </div>
  );
}
```

</details>

<details>
<summary>Vue</summary>

```vue
<script setup>
import { useAtmosphere, useRoom, usePresence } from 'atmosphere.js/vue';

const { state, data, push } = useAtmosphere({ url: '/chat', transport: 'websocket' });

const { joined, members, messages, broadcast } = useRoom(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: 'user-1' },
);

const { count, isOnline } = usePresence(
  { url: '/atmosphere/room', transport: 'websocket' },
  'lobby',
  { id: currentUser.id },
);
</script>

<template>
  <div>
    <p>{{ count }} online</p>
    <div v-for="(m, i) in messages" :key="i">{{ m.member.id }}: {{ m.data.text }}</div>
    <button @click="broadcast({ text: 'Hi' })" :disabled="!joined">Send</button>
  </div>
</template>
```

</details>

<details>
<summary>Svelte</summary>

```svelte
<script>
  import { createAtmosphereStore, createRoomStore, createPresenceStore } from 'atmosphere.js/svelte';

  const { store: chat, push } = createAtmosphereStore({ url: '/chat', transport: 'websocket' });

  const { store: lobby, broadcast } = createRoomStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );

  const presence = createPresenceStore(
    { url: '/atmosphere/room', transport: 'websocket' },
    'lobby',
    { id: 'user-1' },
  );
</script>

{#if $lobby.joined}
  <p>{$presence.count} online</p>
  {#each $lobby.messages as m}
    <div>{m.member.id}: {m.data.text}</div>
  {/each}
  <button on:click={() => broadcast({ text: 'Hi' })}>Send</button>
{:else}
  <p>Connecting…</p>
{/if}
```

</details>

## Kotlin DSL

Builder API with coroutine support:

```kotlin
import org.atmosphere.kotlin.atmosphere

val handler = atmosphere {
    onConnect { resource ->
        println("${resource.uuid()} connected via ${resource.transport()}")
    }
    onMessage { resource, message ->
        resource.broadcaster.broadcast(message)
    }
    onDisconnect { resource ->
        println("${resource.uuid()} left")
    }
}

framework.addAtmosphereHandler("/chat", handler)
```

Coroutine extensions:

```kotlin
broadcaster.broadcastSuspend("Hello!")     // suspends instead of blocking
resource.writeSuspend("Direct message")   // suspends instead of blocking
```

## Clustering

Redis and Kafka broadcasters for multi-node deployments. Messages broadcast on one node are delivered to clients on all nodes.

<details>
<summary>Redis</summary>

Add `atmosphere-redis` to your dependencies. Configuration:

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis connection URL |
| `org.atmosphere.redis.password` | | Optional password |

</details>

<details>
<summary>Kafka</summary>

Add `atmosphere-kafka` to your dependencies. Configuration:

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka broker(s) |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Topic name prefix |
| `org.atmosphere.kafka.group.id` | auto-generated | Consumer group ID |

</details>

## Durable Sessions

Sessions survive server restarts. On reconnection, the client sends its session token and the server restores room memberships, broadcaster subscriptions, and metadata.

```properties
atmosphere.durable-sessions.enabled=true
```

Three `SessionStore` implementations: InMemory (development), SQLite (single-node), Redis (clustered).

## Observability

<details>
<summary>Micrometer metrics</summary>

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
AtmosphereMetrics metrics = AtmosphereMetrics.install(framework, registry);
metrics.instrumentRoomManager(roomManager);
```

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.connections.active` | Gauge | Active connections |
| `atmosphere.broadcasters.active` | Gauge | Active broadcasters |
| `atmosphere.connections.total` | Counter | Total connections opened |
| `atmosphere.messages.broadcast` | Counter | Messages broadcast |
| `atmosphere.broadcast.timer` | Timer | Broadcast latency |
| `atmosphere.rooms.active` | Gauge | Active rooms |
| `atmosphere.rooms.members` | Gauge | Members per room (tagged) |

</details>

<details>
<summary>OpenTelemetry tracing</summary>

```java
framework.interceptor(new AtmosphereTracing(GlobalOpenTelemetry.get()));
```

Creates spans for every request with attributes: `atmosphere.resource.uuid`, `atmosphere.transport`, `atmosphere.action`, `atmosphere.broadcaster`, `atmosphere.room`.

</details>

<details>
<summary>Backpressure</summary>

```java
framework.interceptor(new BackpressureInterceptor());
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.backpressure.highWaterMark` | `1000` | Max pending messages per client |
| `org.atmosphere.backpressure.policy` | `drop-oldest` | `drop-oldest`, `drop-newest`, or `disconnect` |

</details>

<details>
<summary>Cache configuration</summary>

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient` | `1000` | Max cached messages per client |
| `org.atmosphere.cache.UUIDBroadcasterCache.messageTTL` | `300` | Per-message TTL in seconds |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxTotal` | `100000` | Global cache size limit |

</details>

## Requirements

| Java | Spring Boot | Quarkus |
|------|-------------|---------|
| 21+  | 4.0.2+      | 3.21+   |

## Documentation

- [Samples](https://github.com/Atmosphere/atmosphere/tree/main/samples)
- [Wiki](https://github.com/Atmosphere/atmosphere/wiki)
- [AI / LLM Streaming](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming)
- [MCP Server](https://github.com/Atmosphere/atmosphere/wiki/MCP-Server)
- [Durable Sessions](https://github.com/Atmosphere/atmosphere/wiki/Durable-Sessions)
- [Kotlin DSL](https://github.com/Atmosphere/atmosphere/wiki/Kotlin-DSL)
- [Migration Guide](https://github.com/Atmosphere/atmosphere/wiki/Migrating-from-2.x-to-4.0)
- [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/)
- [atmosphere.js API](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API)
- [React / Vue / Svelte bindings](https://github.com/Atmosphere/atmosphere/wiki/Framework-Hooks-React-Vue-Svelte)

## Client Libraries

- **TypeScript/JavaScript**: [atmosphere.js](https://github.com/Atmosphere/atmosphere/tree/main/atmosphere.js) 5.0 (included in this repository)
- **Java/Scala/Android**: [wAsync](https://github.com/Atmosphere/wasync)

## Commercial Support

Available via [Async-IO.org](http://async-io.org)

---

@Copyright 2008-2026 [Async-IO.org](http://async-io.org)
