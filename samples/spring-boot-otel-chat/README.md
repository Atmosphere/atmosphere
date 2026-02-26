# Atmosphere OpenTelemetry Chat Sample

Real-time chat with full **OpenTelemetry distributed tracing** — every WebSocket connect, message, and disconnect creates a trace span viewable in Jaeger.

## What it demonstrates

- **`AtmosphereTracing`** — auto-configured interceptor that creates OTel spans for every Atmosphere request lifecycle (connect → suspend → broadcast → disconnect)
- **Zero-config integration** — just add `opentelemetry-api` + `opentelemetry-sdk` to your classpath, provide an `OpenTelemetry` bean, and the starter auto-registers `AtmosphereTracing`
- **Span attributes** — transport type, resource UUID, broadcaster ID, disconnect reason

## Prerequisites

- JDK 21+
- Docker (for Jaeger)

## Quick start

### 1. Start Jaeger

```bash
docker compose up -d
```

Jaeger UI: [http://localhost:16686](http://localhost:16686)

### 2. Run the app

```bash
cd samples/spring-boot-otel-chat
OTEL_SERVICE_NAME=atmosphere-otel-chat \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
../../mvnw spring-boot:run
```

### 3. Chat

Open [http://localhost:8084](http://localhost:8084) in two browser tabs and exchange messages.

### 4. View traces

Open [Jaeger UI](http://localhost:16686), select service **atmosphere-otel-chat**, and click **Find Traces**. Each chat message generates a span with attributes:

| Attribute | Example |
|---|---|
| `atmosphere.transport` | `WEBSOCKET` |
| `atmosphere.resource.uuid` | `abc-123` |
| `atmosphere.broadcaster` | `/atmosphere/chat` |
| `atmosphere.action` | `CONTINUE` |

## How it works

```
┌─────────────┐     WebSocket     ┌──────────────────┐     OTLP/gRPC     ┌─────────┐
│   Browser    │ ◄──────────────► │  Atmosphere +    │ ──────────────── ► │  Jaeger │
│  (chat UI)   │                  │  AtmosphereTracing│                   │  (UI)   │
└─────────────┘                   └──────────────────┘                   └─────────┘
```

1. `AtmosphereTracingAutoConfiguration` detects `OpenTelemetry` bean on classpath
2. Auto-creates and registers `AtmosphereTracing` interceptor
3. Every Atmosphere request gets a SERVER span with lifecycle events
4. Spans are exported via OTLP to Jaeger

## Cleanup

```bash
docker compose down
```
