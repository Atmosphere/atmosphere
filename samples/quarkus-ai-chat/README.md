# Quarkus AI Chat

Minimal Quarkus app that exposes an Atmosphere `@AiEndpoint` over WebSocket,
streaming LLM tokens through `atmosphere-quarkus-langchain4j` → Quarkus
LangChain4j → an OpenAI-compatible provider (Gemini's compat endpoint by
default; works with any provider Quarkus LangChain4j supports).

## Run

The sample defaults to Gemini's OpenAI-compatible endpoint, but any
OpenAI-compatible provider works.

```bash
export GEMINI_API_KEY=ya29...   # or LLM_API_KEY
cd samples/quarkus-ai-chat
mvn quarkus:dev
```

Open http://localhost:18810/ — type a message, watch the assistant stream a
reply token-by-token.

To swap providers, override the application properties:

```bash
LLM_API_KEY=sk-...                           \
LLM_BASE_URL=https://api.openai.com/v1/      \
LLM_MODEL=gpt-4o-mini                        \
mvn quarkus:dev
```

## What's in the box

| File | Endpoint | Role |
|------|----------|------|
| `AiChat.java` | `/atmosphere/ai-chat` | Basic streaming chat — `@Prompt onPrompt(message, session)` calls `session.stream(message)` |
| `PromptCacheDemoChat.java` | `/atmosphere/ai-chat-with-cache` | Demonstrates `@AiEndpoint(promptCache = CONSERVATIVE)` — second identical prompt emits `ai.cache.hit=true` and replays from `InMemoryResponseCache` (port of the Spring Boot sibling) |
| `RetryDemoChat.java` | `/atmosphere/ai-chat-with-retry` | Demonstrates `@AiEndpoint(retry = @Retry(...))` — `fail-once:<id>` prompts trigger a deterministic transient failure / recovery sequence with observable `retry.attempt=N` metadata |
| `MultiModalChat.java` | `/atmosphere/ai-chat-multimodal` | Demonstrates the multi-modal `Content.Image` wire protocol — `image:<base64>` prompts emit a binary content frame followed by a text acknowledgement |
| `ReviewExtractor.java` + `MovieReview.java` | `/atmosphere/review-extractor` | Demonstrates `@AiEndpoint(responseAs = MovieReview.class)` — framework appends JSON schema to system prompt and emits `EntityStart` / `StructuredField` / `EntityComplete` events |
| `DemoResponseProducer.java` | — | Helper for demo-mode responses when no `LLM_API_KEY` is configured |
| `GeminiCompatCustomizer.java` | — | Drops `frequency_penalty` / `presence_penalty` from the OpenAI request — Gemini's compat endpoint rejects unknown fields. Delete for OpenAI proper. |
| `application.properties` | — | `quarkus.atmosphere.packages` + `quarkus.langchain4j.openai.*` |
| `META-INF/resources/index.html` | — | Meta-redirect to the bundled Atmosphere Console SPA at `/atmosphere/console/` (per commit f8930d62f4) — drives all five endpoints from one UI |

Three ported endpoints (`PromptCacheDemoChat`, `RetryDemoChat`,
`ReviewExtractor`) are byte-for-byte parity with their Spring Boot siblings
except for the package name — the `AgentRuntime` SPI is platform-portable, so
the same `@AiEndpoint` source compiles and runs identically under either
Servlet container. The fourth, `MultiModalChat`, keeps the lower-level
`@AiEndpoint` form here; its Spring Boot sibling now leads with the higher-level
`@Agent` annotation (`MultiModalAgent`, registered at
`/atmosphere/agent/multimodal`), which desugars to the same handler but sources
its persona from a `SKILL.md` instead of an inline path.

## How streaming flows end-to-end

```
browser (atmosphere.js)
   │   prompt over WebSocket
   ▼
QuarkusAtmosphereServlet (atmosphere-quarkus-extension)
   │   AiPipeline → @AiEndpoint dispatch
   ▼
AiChat.onPrompt(message, session)
   │   session.stream(message)
   ▼
LangChain4jAgentRuntime
   │   uses model installed by AtmosphereQuarkusLangChain4jBridge at startup
   ▼
Quarkus LangChain4j StreamingChatModel
   │   SSE stream from provider
   ▼
AtmosphereStreamingResponseHandler.onPartialResponse(token)
   │   session.send(token)
   ▼
DefaultBroadcaster → WebSocket frame → atmosphere.js → DOM
```

## Verifying the bridge

The startup log includes:

```
Auto-wired Quarkus LangChain4j StreamingChatModel (...) into LangChain4jAgentRuntime
AI endpoint registered at /atmosphere/ai-chat (... runtime: langchain4j ...)
Installed features: [atmosphere, atmosphere-quarkus-langchain4j, langchain4j, langchain4j-openai, ...]
```

A `Reply with QUARKUS_OK only.` prompt should round-trip and render
`< QUARKUS_OK` in the chat log.

## gRPC subscriber (atmosphere-quarkus-grpc)

The sample wires the `atmosphere-quarkus-grpc` extension so a Netty gRPC
client can subscribe to the same Broadcaster topics that the WebSocket
clients use. Configuration:

```properties
quarkus.atmosphere.grpc.enabled=true
quarkus.atmosphere.grpc.port=19090
quarkus.atmosphere.grpc.enable-reflection=true
```

When `mvn quarkus:dev` is running, the startup log includes:

```
Atmosphere gRPC server listening on port 19090 (reflection=true)
Installed features: [atmosphere, atmosphere-grpc, ..., atmosphere-quarkus-langchain4j, ...]
```

`atmosphere-grpc` shows in the features list because the deployment
processor produces a `FeatureBuildItem("atmosphere-grpc")`. Server
reflection is on by default so `grpcurl` can discover the service
without a local `.proto` file.

### Listing services with grpcurl

```bash
$ grpcurl -plaintext localhost:19090 list
grpc.reflection.v1.ServerReflection
grpc.reflection.v1alpha.ServerReflection
org.atmosphere.grpc.AtmosphereService

$ grpcurl -plaintext localhost:19090 list org.atmosphere.grpc.AtmosphereService
org.atmosphere.grpc.AtmosphereService.Send
org.atmosphere.grpc.AtmosphereService.Stream
org.atmosphere.grpc.AtmosphereService.Subscribe
```

### Subscribing to a Broadcaster topic

Open a server-streaming subscription on `/atmosphere/ai-chat`:

```bash
grpcurl -plaintext \
    -d '{"type":"SUBSCRIBE","topic":"/atmosphere/ai-chat"}' \
    localhost:19090 \
    org.atmosphere.grpc.AtmosphereService/Subscribe
```

The first frame is an `ACK { type: ACK, topic: "/atmosphere/ai-chat", tracking_id: "..." }`.
After that, every WebSocket-driven AI token broadcast on the same
topic streams back as a `MESSAGE { payload: "<token>" }` until the
client disconnects. Drive the chat from the browser and you should
see the same token stream arriving on both the WebSocket UI and the
`grpcurl` console — Atmosphere's Broadcaster is transport-agnostic.

### Sending a message over gRPC

```bash
grpcurl -plaintext \
    -d '{"type":"MESSAGE","topic":"/atmosphere/ai-chat","payload":"hello from grpc"}' \
    localhost:19090 \
    org.atmosphere.grpc.AtmosphereService/Send
```

The unary `Send` returns an `ACK` and broadcasts the payload to every
subscriber on the topic — including any WebSocket clients open in the
browser. Reverse direction (gRPC → WebSocket) works identically.

The same proto contract works against the Spring Boot starter
(`atmosphere-spring-boot-starter` + `atmosphere-grpc`) — only the
container changes, not the wire format.

## Observability (cache + health + metrics + tracing + governance)

The sample wires the five observability surfaces the
`atmosphere-quarkus-extension` deployment processor ports from the Spring
Boot starter. Each is classpath-gated: drop the corresponding Quarkus
extension and the build step quietly skips registration.

| Surface | Spring Boot parity | Active via |
|---------|--------------------|------------|
| Cache (`BoundedMemoryCache` + `MessageAckInterceptor`) | `AtmosphereCacheAutoConfiguration` | `quarkus.atmosphere.cache-enabled=true` |
| Health check (`AtmosphereHealth` → MicroProfile `HealthCheck`) | `AtmosphereActuatorAutoConfiguration` | `quarkus-smallrye-health` dep |
| Micrometer metrics (`atmosphere.*`) | `AtmosphereMetricsAutoConfiguration` | `quarkus-micrometer-registry-prometheus` dep |
| OTel tracing (`AtmosphereTracing` interceptor) | `AtmosphereTracingAutoConfiguration` | `quarkus-opentelemetry` dep (exporters default to `none`) |
| Governance metrics (`atmosphere.governance.*`) | `AtmosphereGovernanceMetricsAutoConfiguration` | Micrometer + `atmosphere-ai` on classpath |

### Health

```bash
$ curl -s http://localhost:18810/q/health | head -20
{
    "status": "UP",
    "checks": [
        {
            "name": "atmosphere",
            "status": "UP",
            "data": {
                "status": "UP",
                "version": "<atmosphere-version>",
                "handlers": 5,
                "broadcasters": 5,
                "interceptors": 12,
                "connections": 0
            }
        }
    ]
}
```

### Metrics

```bash
$ curl -s http://localhost:18810/q/metrics | grep atmosphere | head -10
atmosphere_connections_active 0.0
atmosphere_broadcasters_active 5.0
atmosphere_broadcast_timer_seconds_count 0.0
atmosphere_messages_broadcast_total 0.0
atmosphere_messages_delivered_total 0.0
atmosphere_ai_active_sessions{provider="atmosphere"} 0.0
```

Drive the chat from the browser, then re-fetch `/q/metrics` — counters
move; `atmosphere.governance.policy.evaluation_*` and
`atmosphere.governance.scope.similarity_*` appear after the first
`@AgentScope`-decorated endpoint evaluates a prompt.

### Tracing

`AtmosphereTracingProducer` binds `AtmosphereTracing` as a framework
interceptor on startup, so every Atmosphere request gets a span
(inspect → suspend → broadcast → disconnect). The exporter defaults to
`none` to avoid noisy connection-refused logs against the default
localhost OTLP endpoint; flip it on with:

```bash
OTEL_TRACES_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4317 \
mvn quarkus:dev
```
