<p align="center">
  <img src="logo.png" alt="Atmosphere" width="120"/>
</p>

# Atmosphere

A transport-agnostic real-time framework for the JVM built around two core abstractions: **Broadcaster** (a named pub/sub channel that fans out messages to subscribers) and **AtmosphereResource** (a connection, regardless of transport). Application code publishes to a Broadcaster; the framework delivers over WebSocket, SSE, Long-Polling, or gRPC — the transport is pluggable and transparent. Rewritten for JDK 21 virtual threads.

[![Maven Central](https://img.shields.io/maven-central/v/org.atmosphere/atmosphere-runtime?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/org.atmosphere/atmosphere-runtime)
[![npm](https://img.shields.io/npm/v/atmosphere.js?label=atmosphere.js&color=blue)](https://www.npmjs.com/package/atmosphere.js)
[![Atmosphere CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml)
[![Atmosphere.js CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml)

## Core Concepts

**Broadcaster** — a named channel. Call `broadcaster.broadcast(message)` and every subscribed resource receives it, regardless of transport. Broadcasters support caching, filtering, and clustering (Redis, Kafka) out of the box.

**AtmosphereResource** — represents a single connection. It wraps the underlying transport (WebSocket frame, SSE event stream, HTTP response, or gRPC stream) behind a uniform API. Resources subscribe to Broadcasters.

**Transport** — the wire protocol. Atmosphere ships with WebSocket, SSE, Long-Polling, and gRPC transports. The transport is selected per-connection and can fall back automatically (e.g., WebSocket → SSE → Long-Polling).

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady(AtmosphereResource r) {
        // r could be WebSocket, SSE, Long-Polling, or gRPC — doesn't matter
        log.info("{} connected via {}", r.uuid(), r.transport());
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage message) {
        // Return value is broadcast to all subscribers
        return message;
    }
}
```

## AI/LLM Token Streaming

Atmosphere also serves as a transport layer for AI applications. Frameworks like Spring AI, LangChain4j, and Embabel handle LLM communication; Atmosphere delivers tokens to the browser in real time over any supported transport.

### What you get

- **`@AiEndpoint` + `@Prompt`** — annotate a class, receive prompts, stream tokens.
- **Built-in LLM client** — zero-dependency `OpenAiCompatibleClient` that talks to OpenAI, Gemini, Ollama, or any OpenAI-compatible API.
- **Adapter SPI** — plug in Spring AI, LangChain4j, or Embabel. Your framework generates tokens; Atmosphere delivers them over the Broadcaster.
- **AI as a room participant** — `LlmRoomMember` joins a Room like any user. The LLM subscribes to the same Broadcaster as human participants.
- **Client hooks** — `useStreaming()` for React/Vue/Svelte.

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
| `LLM_MODEL` | `gemini-2.5-flash`, `gpt-5`, `o3-mini`, `llama3.2`, … | `gemini-2.5-flash` |
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
<summary>Embabel adapter</summary>

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
var assistant = new LlmRoomMember("assistant", client, "gpt-5",
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
    <version>4.0.4</version>
</dependency>
```

For Spring Boot:
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.4</version>
</dependency>
```

For Quarkus:
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.4</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.atmosphere:atmosphere-runtime:4.0.4'
// or
implementation 'org.atmosphere:atmosphere-spring-boot-starter:4.0.4'
// or
implementation 'org.atmosphere:atmosphere-quarkus-extension:4.0.4'
```

### npm (TypeScript/JavaScript client)

```bash
npm install atmosphere.js
```

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| Core runtime | `atmosphere-runtime` | WebSocket, SSE, Long-Polling transport layer (Servlet 6.0+) |
| gRPC transport | `atmosphere-grpc` | gRPC bidirectional streaming transport (grpc-java 1.71) |
| Spring Boot starter | `atmosphere-spring-boot-starter` | Auto-configuration for Spring Boot 4.0.2+ (includes optional gRPC) |
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
| Java client | `atmosphere-wasync` | Async Java client — WebSocket, SSE, streaming, long-polling, and gRPC (JDK 21+) |

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
<summary>gRPC transport</summary>

The starter can also launch a gRPC server alongside the servlet container when `atmosphere-grpc` is on the classpath:

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
    enable-reflection: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.grpc.enabled` | `false` | Enable gRPC transport server |
| `atmosphere.grpc.port` | `9090` | gRPC server port |
| `atmosphere.grpc.enable-reflection` | `true` | Enable gRPC server reflection |

Define a `GrpcHandler` bean to handle gRPC events:

```java
@Bean
public GrpcHandler grpcHandler() {
    return new GrpcHandlerAdapter() {
        @Override
        public void onOpen(GrpcChannel channel) {
            log.info("gRPC client connected: {}", channel.uuid());
        }
        @Override
        public void onMessage(GrpcChannel channel, String message) {
            log.info("gRPC message: {}", message);
        }
    };
}
```

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

### Standalone gRPC

Use the gRPC transport without a servlet container:

```java
var framework = new AtmosphereFramework();

try (var server = AtmosphereGrpcServer.builder()
        .framework(framework)
        .port(9090)
        .handler(new MyGrpcHandler())
        .build()) {
    server.start();
    server.awaitTermination();
}
```

Clients connect using Protocol Buffers over HTTP/2. Test with [grpcurl](https://github.com/fullstorydev/grpcurl):

```bash
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream
```

See the [gRPC chat sample](samples/grpc-chat/) for a complete example.

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
- **Java**: [wAsync](modules/wasync/) 4.x — fluent async client powered by `java.net.http` (JDK 21+), supports WebSocket, SSE, streaming, long-polling, and gRPC

## Commercial Support

Available via [Async-IO.org](https://async-io.org)

---

@Copyright 2008-2026 [Async-IO.org](https://async-io.org)
