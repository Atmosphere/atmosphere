# Configuration Reference

Atmosphere is configured through environment variables, servlet init-params, Spring Boot properties, or Quarkus config. This document covers the main configuration surfaces.

## Environment Variables (AI)

These control LLM provider settings. Read by `AiConfig.fromEnvironment()`.

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud API), `local` (Ollama), or `fake` (no real calls) | `remote` |
| `LLM_MODEL` | Model name (e.g. `gemini-2.5-flash`, `gpt-4o`, `llama3.2`) | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key for the provider | (none) |
| `OPENAI_API_KEY` | Fallback if `LLM_API_KEY` is not set | (none) |
| `GEMINI_API_KEY` | Fallback if neither `LLM_API_KEY` nor `OPENAI_API_KEY` is set | (none) |
| `LLM_BASE_URL` | Override the API endpoint (disables auto-detection) | (auto) |

API key resolution order: `LLM_API_KEY` > `OPENAI_API_KEY` > `GEMINI_API_KEY`.

Base URL auto-detection: `gpt-*`/`o1*`/`o3*` models route to `api.openai.com`, `local` mode routes to `localhost:11434`, all others route to Gemini.

## Environment Variables (Channels)

| Variable | Description |
|----------|-------------|
| `SLACK_BOT_TOKEN` | Slack bot token (`xoxb-...`). Activates Slack channel. |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token. Activates Telegram channel. |
| `DISCORD_BOT_TOKEN` | Discord bot token. Activates Discord channel. |
| `WHATSAPP_ACCESS_TOKEN` | WhatsApp access token. Activates WhatsApp channel. |
| `MESSENGER_PAGE_TOKEN` | Messenger page token. Activates Messenger channel. |

## AI Init-Params (org.atmosphere.ai.*)

Set via `@ManagedService(atmosphereConfig = {...})`, web.xml, or programmatically via `AiConfig.configure()`.

| Init-Param | Description | Default |
|-----------|-------------|---------|
| `org.atmosphere.ai.llmMode` | `remote`, `local`, or `fake` | `remote` |
| `org.atmosphere.ai.llmModel` | Model name | `gemini-2.5-flash` |
| `org.atmosphere.ai.llmApiKey` | API key | (none) |
| `org.atmosphere.ai.llmBaseUrl` | Override API endpoint | (auto) |

## Core Init-Params (org.atmosphere.*)

Set via web.xml `<init-param>`, `@ManagedService(atmosphereConfig = {...})`, or Spring Boot's `atmosphere.init-params` map.

### Transport

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.useWebSocket` | `true` | Force WebSocket support |
| `org.atmosphere.useWebSocketAndServlet3` | `false` | WebSocket + Servlet 3.0 API |
| `org.atmosphere.useBlocking` | `false` | Use blocking I/O (BlockingIOCometSupport) |
| `org.atmosphere.useNative` | `false` | Use container-native Comet support |
| `org.atmosphere.useStream` | `true` | Use stream for writing bytes |
| `org.atmosphere.useVirtualThreads` | `true` | Use virtual threads (JDK 21+) for async I/O |

### WebSocket

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.websocket.maxIdleTime` | 5 min | Max idle time before disconnect |
| `org.atmosphere.websocket.writeTimeout` | 1 min | JSR 356 write timeout |
| `org.atmosphere.websocket.bufferSize` | 8192 | Write buffer size |
| `org.atmosphere.websocket.maxTextMessageSize` | 8192 | Max text frame aggregation |
| `org.atmosphere.websocket.maxBinaryMessageSize` | 8192 | Max binary frame aggregation |
| `org.atmosphere.websocket.requireSameOrigin` | `true` | Enforce same-origin policy |
| `org.atmosphere.websocket.messageContentType` | `text/plain` | Content-type for dispatched messages |
| `org.atmosphere.websocket.messageMethod` | `POST` | HTTP method for dispatched messages |
| `org.atmosphere.websocket.binaryWrite` | `false` | Write binary instead of String |
| `org.atmosphere.websocket.suppressJSR356` | `false` | Suppress JSR 356 detection |
| `org.atmosphere.websocket.webSocketBufferingMaxSize` | 2097152 | In-memory buffer size (2 MB) |

### Broadcaster

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cpr.broadcasterClass` | `DefaultBroadcaster` | Broadcaster implementation |
| `org.atmosphere.cpr.broadcasterFactory` | `DefaultBroadcasterFactory` | BroadcasterFactory implementation |
| `org.atmosphere.cpr.broadcasterCacheClass` | `DefaultBroadcasterCache` | BroadcasterCache implementation |
| `org.atmosphere.cpr.broadcasterLifeCyclePolicy` | `NEVER` | Lifecycle policy |
| `org.atmosphere.cpr.broadcaster.shareableThreadPool` | `true` | Share thread pool across broadcasters |
| `org.atmosphere.cpr.broadcaster.maxProcessingThreads` | unlimited | Max processing threads |
| `org.atmosphere.cpr.broadcaster.maxAsyncWriteThreads` | 200 | Max async write threads |
| `org.atmosphere.cpr.Broadcaster.writeTimeout` | 5 min | Write operation timeout |
| `org.atmosphere.cpr.Broadcaster.supportOutOfOrderBroadcast` | `false` | Allow out-of-order delivery |

### Heartbeat

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.interceptor.HeartbeatInterceptor.heartbeatFrequencyInSeconds` | 60 | Server heartbeat interval |
| `org.atmosphere.interceptor.HeartbeatInterceptor.clientHeartbeatFrequencyInSeconds` | 0 (disabled) | Client heartbeat interval |
| `org.atmosphere.interceptor.HeartbeatInterceptor.paddingChar` | `' '` | Heartbeat padding character |
| `org.atmosphere.interceptor.HeartbeatInterceptor.resumeOnHeartbeat` | `true` | Resume long-polling on heartbeat |

### Rate Limiting

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.rateLimit.maxMessages` | 100 | Max messages per client per window |
| `org.atmosphere.rateLimit.windowSeconds` | 60 | Rate limit window duration |
| `org.atmosphere.rateLimit.policy` | `drop` | `drop` (silent) or `disconnect` |

### Authentication

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.auth.tokenValidator` | (none) | `TokenValidator` implementation class |
| `org.atmosphere.auth.tokenRefresher` | (none) | `TokenRefresher` implementation class |
| `org.atmosphere.auth.queryParam` | `X-Atmosphere-Auth` | Query param name for auth token |
| `org.atmosphere.auth.disconnectOnFailure` | `true` | Disconnect on auth failure |

### Clustering

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis URL |
| `org.atmosphere.redis.password` | (none) | Redis password |
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka bootstrap servers |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Kafka topic prefix |
| `org.atmosphere.kafka.group.id` | auto-generated UUID | Kafka consumer group ID |

### Cache

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cache.maxSize` | 1000 | Max messages per broadcaster (BoundedMemoryCache) |
| `org.atmosphere.cache.ttlSeconds` | 3600 | Message TTL (BoundedMemoryCache) |
| `org.atmosphere.cache.UUIDBroadcasterCache.clientIdleTime` | 60s | Max cached message age (UUIDBroadcasterCache) |
| `org.atmosphere.cache.UUIDBroadcasterCache.invalidateCacheInterval` | 30s | Prune frequency |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxPerClient` | 1000 | Max cached per client |
| `org.atmosphere.cache.UUIDBroadcasterCache.messageTTL` | 300s | Per-message TTL |
| `org.atmosphere.cache.UUIDBroadcasterCache.maxTotal` | 100000 | Global max total cached |

### Session

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cpr.sessionSupport` | `false` | Enable HttpSession support |
| `org.atmosphere.cpr.sessionCreate` | `true` | Create new sessions at startup |
| `org.atmosphere.cpr.removeSessionTimeout` | `true` | Set session max inactive to -1 |
| `org.atmosphere.cpr.session.maxInactiveInterval` | -1 | Max inactive interval (seconds) |

### CORS

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cpr.cors.allowedOrigins` | (not set) | Comma-separated allowed origins. Not set = echo all (insecure). |
| `org.atmosphere.cpr.dropAccessControlAllowOriginHeader` | `false` | Suppress CORS headers entirely |
| `org.atmosphere.cpr.noCacheHeaders` | `false` | Suppress no-cache headers |

### Miscellaneous

| Init-Param | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.cpr.packages` | `""` | Packages to scan for annotations |
| `org.atmosphere.cpr.AtmosphereInterceptor` | `""` | Custom interceptor class names (comma-separated) |
| `org.atmosphere.cpr.AtmosphereInterceptor.disableDefaults` | `false` | Disable default interceptors |
| `org.atmosphere.cpr.defaultContentType` | `text/plain` | Default content type |
| `org.atmosphere.cpr.objectFactory` | `DefaultAtmosphereObjectFactory` | Object factory class |
| `org.atmosphere.cpr.scanClassPath` | `true` | Scan classpath for annotations |
| `org.atmosphere.cpr.AtmosphereInitializer.disabled` | `false` | Disable Servlet 3.0 auto-init |
| `org.atmosphere.cpr.disableContainerPatching` | `false` | Skip JVM-global system property patches |

## Spring Boot Properties (atmosphere.*)

Bound via `@ConfigurationProperties(prefix = "atmosphere")` in `AtmosphereProperties`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `atmosphere.servlet-path` | `String` | `/atmosphere/*` | Servlet mapping path |
| `atmosphere.packages` | `String` | (none) | Packages to scan |
| `atmosphere.order` | `int` | 0 | Servlet registration order |
| `atmosphere.session-support` | `boolean` | `false` | Enable HttpSession |
| `atmosphere.broadcaster-class` | `String` | (none) | Broadcaster class name |
| `atmosphere.broadcaster-cache-class` | `String` | (none) | BroadcasterCache class name |
| `atmosphere.websocket-support` | `Boolean` | (none) | Override WebSocket support |
| `atmosphere.heartbeat-interval` | `Duration` | (none) | Heartbeat interval (e.g. `30s`) |
| `atmosphere.console-subtitle` | `String` | `""` | Console info endpoint subtitle |
| `atmosphere.init-params.*` | `Map` | `{}` | Pass-through init-params to the servlet |
| `atmosphere.grpc.enabled` | `boolean` | `false` | Enable gRPC transport |
| `atmosphere.grpc.port` | `int` | 9090 | gRPC listen port |
| `atmosphere.grpc.enable-reflection` | `boolean` | `true` | Enable gRPC reflection |
| `atmosphere.durable-sessions.enabled` | `boolean` | `false` | Enable durable sessions |
| `atmosphere.durable-sessions.session-ttl` | `Duration` | `1440m` | Session TTL |
| `atmosphere.durable-sessions.cleanup-interval` | `Duration` | `60s` | Cleanup interval |
| `atmosphere.ai.enabled` | `boolean` | `true` | Enable AI auto-configuration |
| `atmosphere.ai.mode` | `String` | `remote` | LLM mode |
| `atmosphere.ai.model` | `String` | `gemini-2.5-flash` | LLM model |
| `atmosphere.ai.api-key` | `String` | (none) | LLM API key |
| `atmosphere.ai.base-url` | `String` | (none) | Override LLM endpoint |
| `atmosphere.ai.path` | `String` | `/atmosphere/ai-chat` | Default AI chat path |
| `atmosphere.ai.system-prompt` | `String` | `You are a helpful assistant.` | Default system prompt |
| `atmosphere.ai.system-prompt-resource` | `String` | (none) | Classpath resource for system prompt |
| `atmosphere.ai.conversation-memory` | `boolean` | `true` | Enable conversation memory |
| `atmosphere.ai.max-history-messages` | `int` | 20 | Max conversation history size |
| `atmosphere.ai.timeout` | `long` | 120000 | AI request timeout (ms) |

Any `org.atmosphere.*` init-param can also be passed via `atmosphere.init-params`:

```yaml
atmosphere:
  init-params:
    org.atmosphere.cpr.broadcasterCacheClass: org.atmosphere.cache.UUIDBroadcasterCache
    org.atmosphere.rateLimit.maxMessages: "50"
```

## Quarkus Properties (quarkus.atmosphere.*)

Bound via `@ConfigMapping(prefix = "quarkus.atmosphere")` with `BUILD_AND_RUN_TIME_FIXED` phase.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `quarkus.atmosphere.servlet-path` | `String` | `/atmosphere/*` | Servlet mapping path |
| `quarkus.atmosphere.packages` | `String` | (optional) | Packages to scan |
| `quarkus.atmosphere.load-on-startup` | `int` | 1 | Servlet load order. Must be > 0 (Quarkus skips <= 0). |
| `quarkus.atmosphere.session-support` | `boolean` | `false` | Enable HttpSession |
| `quarkus.atmosphere.broadcaster-class` | `String` | (optional) | Broadcaster class name |
| `quarkus.atmosphere.broadcaster-cache-class` | `String` | (optional) | BroadcasterCache class name |
| `quarkus.atmosphere.websocket-support` | `Boolean` | (optional) | Override WebSocket support |
| `quarkus.atmosphere.heartbeat-interval` | `Duration` | (optional) | Heartbeat interval (e.g. `30s`) |
| `quarkus.atmosphere.init-params.*` | `Map` | `{}` | Pass-through init-params |

## HTTP Headers (X-Atmosphere-*)

Headers exchanged between client and server. Defined in `HeaderConfig`.

| Header | Direction | Description |
|--------|-----------|-------------|
| `X-Atmosphere-Transport` | Client -> Server | Requested transport (`websocket`, `sse`, `long-polling`, `streaming`) |
| `X-Atmosphere-tracking-id` | Server -> Client | Unique connection tracking ID |
| `X-Atmosphere-Framework` | Server -> Client | Atmosphere version |
| `X-Atmosphere-first-request` | Client -> Server | First request marker |
| `X-Atmosphere-TrackMessageSize` | Both | Message size tracking |
| `X-Atmosphere-error` | Server -> Client | Error description |
| `X-Heartbeat-Server` | Server -> Client | Heartbeat interval in seconds |
| `X-Atmosphere-Post-Body` | Client -> Server | POST body passthrough |
| `X-Atmosphere-Binary` | Both | Binary message marker |
| `X-Atmosphere-Auth` | Client -> Server | Authentication token |
| `X-Atmosphere-Auth-Refresh` | Server -> Client | Refreshed token |
| `X-Atmosphere-Auth-Expired` | Server -> Client | Token expired signal |
| `X-Atmosphere-Message-Id` | Client -> Server | Client-assigned message ID for ack tracking |
| `X-Atmosphere-Ack` | Server -> Client | Server acknowledgment of message receipt |
| `X-atmo-protocol` | Both | Protocol negotiation |
