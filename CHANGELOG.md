# Changelog

All notable changes to the Atmosphere Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.0.36] - 2026-04-13

### Added

#### Seventh AI Runtime

- **Microsoft Semantic Kernel adapter** (`atmosphere-semantic-kernel`). Seventh
  `AgentRuntime` implementation backed by Semantic Kernel's
  `ChatCompletionService`. Streams via `getStreamingChatMessageContentsAsync`,
  honors system prompts, and threads `AgentExecutionContext` metadata through
  the SK invocation context. Tool-calling is deferred in 4.0.36 (SK's native
  function-call loop is not yet bridged). `SemanticKernelEmbeddingRuntime`
  ships alongside for embedding support via SK's `EmbeddingGenerationService`.

#### Unified `@Agent` API close-out (Waves 1-6)

- **`ToolApprovalPolicy`** — sealed interface on `AgentExecutionContext` with
  four implementations: `annotated()` (default, honors `@RequiresApproval`),
  `allowAll()` (trusted test fixtures), `denyAll()` (preview/shadow mode, no
  invocation ever runs), and `custom(Predicate<ToolDefinition>)` for runtime
  decisions. Every tool-calling runtime threads the policy through
  `ToolExecutionHelper.executeWithApproval()` and the policy check runs
  **before** the session-scoped `ApprovalStrategy` is consulted.
- **`ExecutionHandle`** — first-class cooperative cancellation for in-flight
  executions via `AgentRuntime.executeWithHandle(context, session)`. Returns
  an `ExecutionHandle` with idempotent `cancel()`, terminal `whenDone()`
  future, and `isDone()`. Each runtime wraps its native cancel primitive:
  Built-in closes the HTTP `InputStream`, Spring AI / ADK dispose the Reactor
  `Flux`, Koog cancels the `runBlocking` coroutine Job, LC4j completes the
  `CompletableFuture` exceptionally with a soft-cancel flag. Closes
  Correctness Invariant #2 (Terminal Path Completeness).
- **`AgentLifecycleListener`** — observability SPI with `onStart`,
  `onToolCall`, `onToolResult`, `onCompletion`, `onError`. Attach via
  `AgentExecutionContext.withListeners(...)`. All seven runtimes fire the
  full event set; error isolation is guaranteed via static
  `fireToolCall`/`fireToolResult` dispatchers that swallow listener
  exceptions so a failing listener never breaks the tool-call loop.
- **`EmbeddingRuntime` SPI** (`atmosphere-ai` module) with `float[] embed()`,
  `List<float[]> embedAll()`, `int dimensions()`, `isAvailable()`, `name()`,
  `priority()`. Five implementations ship with a priority chain:
  `SpringAiEmbeddingRuntime` (200) > `LangChain4jEmbeddingRuntime` (190) >
  `SemanticKernelEmbeddingRuntime` (180) > `EmbabelEmbeddingRuntime` (170) >
  `BuiltInEmbeddingRuntime` (50, OpenAI-compatible HTTP). Service-loader
  resolution picks the highest-priority available implementation at runtime.
- **Per-request `RetryPolicy`** on `AgentExecutionContext` — declarative
  retry with `maxRetries`, `initialDelay`, `backoffMultiplier`, and jitter.
  Built-in runtime honors the policy natively; framework runtimes inherit
  their own native retry layers and the per-request policy is Built-in only
  in 4.0.36 (documented in the per-runtime capability matrix).
- **`CacheHint`** — prompt-caching hint threaded through
  `AgentExecutionContext.metadata()`. Policies: `CONSERVATIVE` (session
  lifetime), `AGGRESSIVE` (cross-session), `NONE` (disable), with fallback
  to session-ID-scoped cache key. The pipeline-layer `ResponseCache` honors
  the hint using a SHA-256 `CacheKey` over model, system prompt, message,
  history, tools, and content parts — session-ID-independent so identical
  prompts hit the same cache line.
- **Multi-modal `Content`** — sealed `Content` type with `Text`, `Image`,
  `Audio`, `File` subtypes for multi-modal LLM input. Wire frames carry
  base64-encoded payloads with explicit `mimeType` and `contentType`
  metadata. Runtimes that do not support multi-modal input declare the
  exclusion in their capability set (Correctness Invariant #5).
- **`session.toolCallDelta()`** — incremental tool-argument streaming so
  clients can render partial JSON as the model generates it. All seven
  runtimes forward tool-call deltas when the underlying provider emits them.
- **`models()` runtime truth** — `AgentRuntime.models()` returns the list of
  models actually reachable by the resolved runtime instance, replacing the
  configuration-intent flag from 4.0.35. The admin control plane
  `/admin/runtime` endpoint exposes this as runtime-resolved truth.
- **`TokenUsage` record** — unified `input`, `output`, `total`, `cached`
  token accounting reported on completion metadata as `ai.tokens.input`,
  `ai.tokens.output`, `ai.tokens.total`, `ai.tokens.cached`.
- **`@AiEndpoint.promptCache()` and `@AiEndpoint.retry()`** — declarative
  annotations for cache hint and retry policy that work identically across
  Spring Boot and Quarkus. The annotation processor runs at build time in
  Quarkus via the Atmosphere extension and wires into the Undertow
  deployment; Spring Boot resolves them at bean post-processing.
- **`AbstractAgentRuntimeContractTest`** — TCK that every `AgentRuntime`
  subclass must pass. Exercises text streaming, tool calling, tool approval
  (all four `ToolApprovalPolicy` variants), system prompt, conversation
  memory, lifecycle listeners, execution cancel, and structured output
  against each runtime implementation. Guarantees cross-runtime behavioral
  parity (Correctness Invariant #7 — Mode Parity).

#### Unified `@Agent` + `@Command` (Wave 0-1 — released in 4.0.36)

- **`@Agent` + `@Command` — unified agent annotation.** Define an agent with
  `@Agent(name, skillFile)`, add slash commands with `@Command`, and the
  framework auto-generates `@AiEndpoint`, A2A Agent Card, MCP tools, AG-UI
  events, and channel routing based on classpath detection. Commands work on
  **every channel** — Web, Slack, Telegram, Discord, WhatsApp, Messenger —
  with zero per-channel code.
- **`atmosphere-agent`** module — annotation processor, `CommandRouter`,
  `SkillFileParser`, and `AgentHandler`.
- **`skill.md`** — markdown files that serve as both the LLM system prompt
  and agent metadata. Sections (`## Skills`, `## Tools`, `## Channels`,
  `## Guardrails`) are parsed for A2A Agent Card, MCP tool manifests, and
  channel validation.
- **`@Command` cross-channel routing** — slash commands defined once are
  routed on Web (WebSocket), Slack, Telegram, Discord, WhatsApp, and
  Messenger when `atmosphere-channels` is on the classpath. Includes
  confirmation flow for destructive actions and auto-generated `/help`.
- **JetBrains Koog adapter** (`atmosphere-koog`). Sixth `AgentRuntime`
  backed by Koog's `AIAgent` / `chatAgentStrategy()` with tool calling via
  `AtmosphereToolBridge`, cooperative cancel via `runBlocking` coroutine
  `Job`, and real-time streaming through Koog's `PromptExecutor`. Works
  with Gemini, OpenAI, and all Koog providers.
- **Orchestration primitives** — agent handoffs
  (`session.handoff(target, message)`), approval gates
  (`@RequiresApproval`), conditional routing (`fleet.route(result, spec)`),
  long-term memory (`LongTermMemoryInterceptor`), and LLM-as-judge eval
  assertions (`LlmJudge.meetsIntent()`, `.isGroundedIn()`, `.hasQuality()`).
- **Samples**: `spring-boot-dentist-agent` (Dr. Molar emergency dental
  agent with `/firstaid`, `/urgency`, `/pain` and `assess_emergency` /
  `pain_relief` tools across Slack + Telegram);
  `spring-boot-orchestration-demo` (support/billing handoff with
  `@RequiresApproval` on `cancel_account`);
  `spring-boot-checkpoint-agent` (durable HITL workflow surviving JVM
  restart via `CheckpointStore`).

#### End-to-end coverage

- **Nine new Playwright specs** in `modules/integration-tests/e2e/`
  covering multi-modal content wire frames, `CacheHint` policy +
  resolved cache key, `EmbeddingRuntime` vector shape, per-request
  `RetryPolicy` echo, `toolCallDelta` incremental streaming,
  `AgentLifecycleListener` event ordering, `models()` runtime truth,
  real HITL approval flow (approve + deny paths), and the un-orphaned
  HITL approval project wired into the `ai-tools` CI group.
- **Ollama real-LLM tier** (`.github/workflows/e2e-real-llm.yml`) —
  push-to-main CI job that exercises Built-in, Spring AI, and LC4j
  runtimes against a containerized Ollama with `qwen2.5:0.5b`.
  Structural assertions only (response non-empty, JSON parses, vector
  dimensions > 0).
- **Paid nightly tier** (`.github/workflows/e2e-real-llm-paid.yml`) —
  scheduled OpenAI + Gemini coverage for tool calling, multi-modal
  input, prompt caching, and HITL approval with real tool calls. Cost
  ceiling ≤$5/run, hard 30s timeout per test.

### Fixed

- **`ToolApprovalPolicy.DenyAll` bypass (P0 security).**
  `DenyAll.requiresApproval()` previously returned `true` and delegated to
  the session-scoped `ApprovalStrategy`, so an auto-approve strategy could
  silently run a tool that the caller intended to deny. The fix in
  `ToolExecutionHelper.executeWithApproval` now detects `DenyAll`
  specifically and returns
  `{"status":"cancelled","message":"Tool execution denied by policy"}`
  without consulting the strategy. Closes Correctness Invariant #6
  (fail-closed default).
- **`ToolApprovalPolicy` not threaded through tool-loop reconstruction
  (P1).** Round 2+ of a multi-round tool execution previously rebuilt the
  `AgentExecutionContext` without the caller's approval policy, silently
  reverting to `annotated()`. All six tool-calling runtimes now preserve
  `approvalPolicy` across tool-loop rounds.
- **`tryResolve` classpath-only runtime detection (P1).** The admin
  control plane and capability advertisement previously reported
  runtimes as available based on `Class.forName()` success. They now
  require a successful `isAvailable()` probe and a live
  `AgentRuntimeResolver.resolve()` call. Honest runtime truth
  (Correctness Invariant #5).
- **Koog cancel deadlock (P1).** `KoogAgentRuntime.executeWithHandle`
  previously stored the `runBlocking` coroutine `Job` inside the
  blocking frame, so `handle.cancel()` racing with natural completion
  could deadlock the virtual thread. The reference is now captured in
  an `AtomicReference` before `runBlocking` enters, and the cancel
  callback reads it without blocking.
- **`ResponseCache` observability gap (P1).** Cache hits previously
  fired no lifecycle events, so tracing and metrics showed a gap on
  cache-hit paths. The pipeline now fires `onStart` + synthetic
  `onCompletion` on cache hit with `ai.cache.hit=true` metadata.
- **`CacheKey` missing output-affecting fields (P1).** The hash
  previously omitted `maxTokens`, `temperature`, and `topP`, so two
  requests with identical prompts but different generation parameters
  shared a cache line. All output-affecting fields are now included in
  the SHA-256 input.
- **LC4j premature completion (P1).** `LangChain4jStreamingAdapter`
  previously completed the handle future when the first tool-call
  delta arrived, before the tool loop finished. It now waits for the
  terminal `onComplete` callback.
- **ADK model override ignored (P1).** `AdkAgentRuntime` previously
  built its `LlmAgent` from the module-level default model and ignored
  `AgentExecutionContext.model()`. The context value now takes
  precedence.
- **Release automation stale-version patterns (P1).** Two gaps in
  `scripts/update-doc-versions.sh` left `README.md` "Current release"
  and `cli/sdkman/*.md` `publish.sh` examples pointing at the previous
  release after `release-4x.yml` ran. Both patterns are now swept on
  every release.
- **Cross-repo sync on release.** `release-4x.yml` now fires a
  `repository_dispatch` event at `Atmosphere/atmosphere.github.io` via
  the existing `SITE_DISPATCH_TOKEN` on successful release. A
  companion `sync-version.yml` workflow in the docs repo runs
  `scripts/update-doc-versions.sh` against every tutorial + website
  reference and commits the result. Closes the long-standing gap where
  the docs site lagged the Maven Central release by days.

## [4.0.11] - 2026-03-11

### Fixed

- **WebSocket XSS sanitization bypass.** Disabled HTML sanitization for
  WebSocket transport — HTML-encoding JSON in WebSocket frames broke the
  AI streaming wire protocol.
- **XSS and insecure cookie hardening.** Sanitize HTML output in write
  methods and set the `Secure` flag on cookies over HTTPS.

### Changed

- **Token → Streaming Text rename.** All AI module APIs, javadoc,
  and the atmosphere.js client now use "streaming text" instead of "token"
  to describe LLM output chunks. This affects method names
  (`onToken` → `onStreamingText`, `totalTokens` → `totalStreamingTexts`),
  field names, and the wire protocol message type
  (`"token"` → `"streaming-text"`). This is a **breaking change** for
  atmosphere.js consumers and custom `AiStreamBroadcastFilter`
  implementations.
- **Javadoc published to GitHub Pages.** API docs for `atmosphere-runtime`
  are now deployed automatically to `async-io.org/apidocs`.
- **Starlight tutorial site.** A 20-chapter tutorial book is now available
  at the project documentation site.

## [4.0.3] - 2026-02-22

### Fixed

- **Room Protocol broadcast bug.** `DefaultRoom.broadcast()` now wraps messages
  in `RawMessage` to bypass `@Message` decoder mangling. Room JSON envelopes
  (join/leave/message events) are delivered intact to clients.
- **`enableHistory()` NPE.** `UUIDBroadcasterCache` is now properly configured
  before use, preventing `NullPointerException` when room history is enabled.
- **Native Image build.** Spring Boot samples use `process-aot` and `exec`
  classifier in the `native` profile so GraalVM can find the main class.

### Added

- **`RawMessage` API** (`org.atmosphere.cpr.RawMessage`) — first-class public
  wrapper for pre-encoded messages that bypass `@Message` decoder/encoder
  pipelines. `ManagedAtmosphereHandler.Managed` is deprecated in favor of
  `RawMessage`.
- **Playwright E2E tests** for all sample applications (chat, spring-boot-chat,
  embedded-jetty, quarkus-chat, AI samples, durable-sessions, MCP server).

### Changed

- **Unified parent POM.** All samples now inherit from `atmosphere-project`,
  making `mvn versions:set` update every module in a single command.
- **Normalized artifact names.** All modules use lowercase kebab-case
  `atmosphere-*` naming consistently.
- **Release workflow hardened.** Stale tags are cleaned before tagging, and
  `git rebase` handles diverged branches during release builds.

## [4.0.0] - 2026-02-18

Atmosphere 4.0 is a rewrite of the framework for JDK 21+ and Jakarta EE 10.
It keeps the annotation-driven programming model and transport abstraction from
prior versions, and adds support for virtual threads, AI/LLM streaming, rooms
and presence, native image compilation, and frontend framework bindings.

This release succeeds the 2.x/3.x line (last release: 3.1.0 / 2.7.16). The
`javax.servlet` namespace, Java 8 runtime, and legacy application server
integrations have been removed. Applications migrating from 2.x or 3.x should
consult the [Migration Guide](https://atmosphere.github.io/docs/tutorial/22-migration/).

### Added

#### Platform and Runtime

- **JDK 21 minimum requirement.** The framework compiles with `--release 21`
  and is tested on JDK 21, 23, and 25 in CI.
- **Jakarta EE 10 baseline.** All Servlet, WebSocket, and CDI APIs use the
  `jakarta.*` namespace. Servlet 6.0, WebSocket 2.1, and CDI 4.0 are the
  minimum supported versions.
- **Virtual Thread support.** `ExecutorsFactory` creates virtual-thread-per-task
  executors by default via `Executors.newVirtualThreadPerTaskExecutor()`.
  `DefaultBroadcaster` and 16 other core classes have been migrated from
  `synchronized` blocks to `ReentrantLock` to avoid virtual thread pinning.
  Virtual threads can be disabled with
  `ApplicationConfig.USE_VIRTUAL_THREADS=false`.
- **GraalVM native image support.** Both the Spring Boot starter and Quarkus
  extension include reflection and resource hints for ahead-of-time
  compilation. Spring Boot requires GraalVM 25+; Quarkus works with
  GraalVM 21+ or Mandrel.

#### New Modules

- **`atmosphere-spring-boot-starter`** -- Spring Boot 4.0 auto-configuration
  with annotation scanning, Spring DI bridge (`SpringAtmosphereObjectFactory`),
  Actuator health indicator (`AtmosphereHealthIndicator`), and GraalVM AOT
  runtime hints (`AtmosphereRuntimeHints`). Configuration via
  `atmosphere.*` properties in `application.yml`.
- **`atmosphere-quarkus-extension`** (runtime + deployment) -- Quarkus 3.21+
  extension with build-time Jandex annotation scanning, Arc CDI integration,
  custom `QuarkusJSR356AsyncSupport`, and `@BuildStep`-driven native image
  registration. Configuration via `quarkus.atmosphere.*` properties.
- **`atmosphere-ai`** -- AI/LLM streaming SPI. Defines `StreamingSession`,
  `StreamingSessions`, `AiStreamingAdapter`, and `AiConfig` for streaming
  streaming texts from any LLM provider to connected clients. Includes the
  `@AiEndpoint` annotation for zero-boilerplate AI handlers and the `@Prompt`
  annotation for marking prompt-handling methods that run on virtual threads
  automatically.
- **`atmosphere-spring-ai`** -- Spring AI adapter
  (`SpringAiStreamingAdapter`) that bridges `ChatClient` streaming responses
  to `StreamingSession`.
- **`atmosphere-langchain4j`** -- LangChain4j adapter
  (`LangChain4jStreamingAdapter`, `AtmosphereStreamingResponseHandler`) for
  callback-based LLM streaming.
- **`atmosphere-embabel`** -- Embabel Agent Framework adapter for agentic AI
  with progress events.
- **`atmosphere-mcp`** -- Model Context Protocol (MCP) server module.
  Annotation-driven tools (`@McpTool`), resources (`@McpResource`), prompts
  (`@McpPrompt`), and server declaration (`@McpServer`). Supports WebSocket
  transport, Streamable HTTP transport (MCP 2025-03-26 spec), stdio bridge
  for Claude Desktop, and a programmatic `McpRegistry` API.
- **`atmosphere-kotlin`** -- Kotlin DSL (`atmosphere { ... }` builder) and
  coroutine extensions (`broadcastSuspend`, `writeSuspend`) for idiomatic
  Kotlin integration. Requires Kotlin 2.1+.
- **`atmosphere-redis`** -- Redis clustering broadcaster using Lettuce 6.x
  for non-blocking pub/sub. Messages broadcast on any node are delivered to
  clients connected to all other nodes.
- **`atmosphere-kafka`** -- Kafka clustering broadcaster using the Apache
  Kafka client 3.x. Configurable topic prefix, consumer group, and bootstrap
  servers.
- **`atmosphere-durable-sessions`** -- Durable session SPI with
  `DurableSessionInterceptor`, `SessionStore` interface, and in-memory
  implementation. Sessions survive server restarts; room memberships,
  broadcaster subscriptions, and metadata are restored on reconnection.
- **`atmosphere-durable-sessions-sqlite`** -- SQLite-backed `SessionStore`
  for single-node deployments.
- **`atmosphere-durable-sessions-redis`** -- Redis-backed `SessionStore`
  for clustered deployments.
- **`atmosphere-integration-tests`** -- Integration test suite with embedded
  Jetty and Testcontainers covering WebSocket, SSE, long-polling transports,
  Redis and Kafka clustering, and MCP protocol compliance.

#### Rooms and Presence

- **Room API** (`org.atmosphere.room`). `RoomManager` creates and manages
  named rooms backed by dedicated `Broadcaster` instances. `Room` supports
  `join`, `leave`, `broadcast`, presence tracking via `onPresence` callbacks,
  and configurable message history replay for late joiners.
- **Room protocol** (`org.atmosphere.room.protocol`). `RoomProtocolMessage`
  is a sealed interface with `Join`, `Leave`, `Broadcast`, and `Direct`
  record subtypes, enabling exhaustive pattern matching in Java 21 switch
  expressions.
- **`@RoomService` annotation** for declarative room handler registration
  with automatic `Room` creation via `RoomManager`.
- **`VirtualRoomMember`** for adding LLM agents as room participants.
- **Room authorization** (`RoomAuth`, `RoomAuthorizer`) for controlling
  room access.
- **`RoomProtocolInterceptor`** for automatic protocol message parsing
  and dispatching.

#### Observability

- **Micrometer metrics** (`AtmosphereMetrics`). Registers gauges, counters,
  and timers on an `AtmosphereFramework` instance: active connections,
  active broadcasters, total connections, messages broadcast, broadcast
  latency, room-level gauges, cache hit/miss/eviction counters, and
  backpressure drop/disconnect metrics. Requires `micrometer-core` on the
  classpath (optional dependency).
- **OpenTelemetry tracing** (`AtmosphereTracing`). Interceptor that creates
  spans for every request lifecycle with attributes: `atmosphere.resource.uuid`,
  `atmosphere.transport`, `atmosphere.action`, `atmosphere.broadcaster`,
  `atmosphere.room`. Requires `opentelemetry-api` on the classpath (optional
  dependency).
- **Health check** (`AtmosphereHealth`). Framework-level health snapshot
  reporting status, version, active connections, and broadcaster count.
  Integrated into the Spring Boot Actuator health endpoint via
  `AtmosphereHealthIndicator`.
- **MDC interceptor** (`MDCInterceptor`). Sets `atmosphere.uuid`,
  `atmosphere.transport`, and `atmosphere.broadcaster` in the SLF4J MDC
  for structured logging.

#### Interceptors

- **`BackpressureInterceptor`** -- protects against slow clients with
  configurable high-water mark (default 1000 pending messages) and overflow
  policies: `drop-oldest`, `drop-newest`, or `disconnect`.

#### Client Library

- **atmosphere.js 5.0** -- TypeScript rewrite with no runtime
  dependencies. Ships as ESM, CJS, and IIFE bundles.
- **Transport fallback** -- WebSocket with configurable fallback to SSE,
  HTTP streaming, or long-polling. Full protocol handler with heartbeat,
  reconnection, and message tracking.
- **React hooks** -- `useAtmosphere`, `useRoom`, `usePresence`,
  `useStreaming` via `atmosphere.js/react`. Includes `AtmosphereProvider`
  for connection lifecycle management.
- **Vue composables** -- `useAtmosphere`, `useRoom`, `usePresence`,
  `useStreaming` via `atmosphere.js/vue`.
- **Svelte stores** -- `createAtmosphereStore`, `createRoomStore`,
  `createPresenceStore`, `createStreamingStore` via `atmosphere.js/svelte`.
- **AI streaming client** -- `subscribeStreaming` with `onStreamingText`,
  `onProgress`, `onComplete`, and `onError` callbacks for real-time LLM
  streaming text display.
- **Room and presence client API** -- join/leave rooms, broadcast within
  rooms, track online members, and display presence counts.
- **Chat UI components** -- shared React chat components for sample
  applications via `atmosphere.js/chat`.

#### Samples

- `spring-boot-chat` -- Spring Boot 4 chat application with React frontend.
- `quarkus-chat` -- Quarkus 3.21+ chat application.
- `chat` -- Standalone Jetty embedded chat.
- `embedded-jetty-websocket-chat` -- Embedded Jetty with WebSocket.
- `grpc-chat` -- Standalone gRPC transport chat.
- `spring-boot-ai-chat` -- Streaming AI chat via the `AgentRuntime` SPI.
- `spring-boot-ai-tools` -- Portable `@AiTool` tool calling across runtimes.
- `spring-boot-ai-classroom` -- Multi-room AI with a React Native / Expo client.
- `spring-boot-rag-chat` -- RAG chat with `ContextProvider`.
- `spring-boot-mcp-server` -- MCP server with annotation-driven tools.
- `spring-boot-durable-sessions` -- Durable sessions with SQLite backend.

#### Build and CI

- **Multi-JDK CI** -- GitHub Actions matrix testing on JDK 21, 23, and 25.
- **Native image CI** -- GraalVM native builds for both Spring Boot and
  Quarkus with smoke tests.
- **atmosphere.js CI** -- TypeScript build, test, lint, and bundle size
  verification.
- **Samples CI** -- Compilation verification for all sample applications
  including frontend npm builds.
- **Unified release workflow** (`release-4x.yml`) for coordinated Maven
  Central and npm publishing.
- **CodeQL analysis** for automated security scanning.
- **Pre-commit hooks** enforcing Apache 2.0 copyright headers and
  conventional commit message format.
- **Checkstyle and PMD** enforced in the `validate` phase with
  `failsOnError=true`.

### Changed

#### Platform Migration

- Java 8 minimum raised to **Java 21**. All source compiled with
  `--release 21`.
- `javax.servlet` namespace replaced with **`jakarta.servlet`** throughout
  the codebase.
- Jetty 9 support replaced with **Jetty 12** (`12.0.16`).
- Tomcat 8 support replaced with **Tomcat 11** (`11.0.18`).
- SLF4J upgraded from 1.x to **2.0.16**; Logback from 1.2.x to **1.5.18**.

#### Concurrency

- `synchronized` blocks in `DefaultBroadcaster`, `AtmosphereResourceImpl`,
  `AsynchronousProcessor`, and 13 other core classes replaced with
  `ReentrantLock` for virtual thread compatibility.
- `HashMap` and `ArrayList` in concurrent contexts replaced with
  `ConcurrentHashMap` and `CopyOnWriteArrayList`.
- `ScheduledExecutorService` remains on platform threads for timed tasks
  (expected -- virtual threads do not benefit from scheduling).

#### Language Modernization

- `instanceof` checks replaced with **pattern matching** throughout the
  codebase.
- `if/else` chains on enums replaced with **switch expressions** (JDK 21).
- Immutable collection factories (`List.of()`, `Map.of()`, `Set.of()`)
  used in place of `Collections.unmodifiable*` wrappers.
- Lambda expressions replace anonymous inner classes where appropriate.
- `String.repeat()` replaces manual loop concatenation.
- Diamond operator applied consistently.
- `try-with-resources` applied to all `AutoCloseable` usage.
- `var` used for local variables where the type is obvious from context.
- **Records** used for room protocol messages (`Join`, `Leave`, `Broadcast`,
  `Direct`), cache entries, and event types.
- **Sealed interfaces** used for `RoomProtocolMessage` and related type
  hierarchies.

#### Client Library

- atmosphere.js rewritten from jQuery-based JavaScript to **TypeScript with
  zero runtime dependencies**.
- Package renamed to `atmosphere.js` on npm, version 5.0.0.
- Build tooling changed from Grunt/Bower to **tsup** (esbuild-based
  bundler) with **Vitest** for testing.
- Module format changed from AMD/global to **ESM + CJS + IIFE** triple
  output.
- Peer dependencies on React 18+, Vue 3.3+, and Svelte 4+ are all
  optional.

#### Testing

- TestNG retained for core `atmosphere-runtime` tests.
- **JUnit 5** adopted for Spring Boot starter tests (via
  `spring-boot-starter-test`).
- **JUnit 5** adopted for Quarkus extension tests (via `quarkus-junit5`).
- Mockito upgraded to **5.21.0** for JDK 25 compatibility (ByteBuddy
  1.17.7).
- Integration tests use **Testcontainers** for Redis and Kafka.
- `JSR356WebSocketTest` excluded (Mockito cannot mock sealed interfaces
  on JDK 21+).

#### Architecture

- **`AtmosphereFramework` decomposed** into focused component classes.
  The former 3,400-line god object is now an orchestrator (~2,260 lines)
  that delegates to single-responsibility components. The public API is
  fully preserved -- all existing `framework.addAtmosphereHandler()`,
  `framework.interceptor()`, etc. calls continue to work unchanged.
  New internal components:
  - `BroadcasterSetup` -- broadcaster configuration, factory, and lifecycle
  - `ClasspathScanner` -- annotation scanning, handler/WebSocket auto-detection
  - `InterceptorRegistry` -- interceptor lifecycle and ordering
  - `HandlerRegistry` -- handler registration and endpoint mapping
  - `WebSocketConfig` -- WebSocket protocol and processor configuration
  - `FrameworkEventDispatcher` -- listener management and lifecycle events
  - `FrameworkDiagnostics` -- startup diagnostics and analytics reporting
- **`AtmosphereHandlerWrapper` fields encapsulated.** Previously public
  mutable fields (`broadcaster`, `interceptors`, `mapping`) are now
  private with accessor methods.
- **Inner classes promoted to top-level.** `AtmosphereHandlerWrapper`,
  `MetaServiceAction`, and `DefaultAtmosphereObjectFactory` are now
  standalone classes in `org.atmosphere.cpr`.

#### Build

- Legacy Maven repositories (Codehaus, maven.java.net, JBoss Nexus,
  Sonatype) removed. All dependencies sourced from **Maven Central**.
- Publishing migrated from legacy OSSRH to the **Central Publishing
  Portal** (`central-publishing-maven-plugin`).
- CDDL-licensed Jersey utility classes (`UriTemplate`, `PathTemplate`)
  replaced with Apache 2.0 implementations.
- OSGi bundle configuration updated for `jakarta.*` imports.

### Removed

- **Java 8, 11, and 17 support.** JDK 21 is the minimum.
- **`javax.servlet` namespace.** All APIs use `jakarta.*`.
- **Legacy application server support.** GlassFish 3/4, Jetty 6-9,
  Tomcat 6-8, WebLogic, JBoss AS 7, and Netty-based transports are no
  longer supported. The framework targets Servlet 6.0+ containers (Jetty
  12, Tomcat 11, Undertow via Quarkus).
- **Deprecated APIs.** Two passes of deprecated code removal
  (`cf24377f0`, `a8e6f2be3`) cleaned out dead code paths, unused
  configuration options, and obsolete utility classes accumulated over the
  2.x/3.x lifecycle.
- **CDDL-licensed code.** Jersey-derived `UriTemplate` and related classes
  removed and replaced with Apache 2.0 implementations.
- **jQuery dependency in atmosphere.js.** The client library has zero
  runtime dependencies.
- **Netty, Play Framework, and Vert.x integrations.** These have been
  moved to a legacy section and are no longer maintained.

### Migration Notes

#### Server-side

1. **Update your JDK.** Atmosphere 4.0 requires JDK 21 or later.
2. **Replace `javax.servlet` imports with `jakarta.servlet`.** This
   includes `HttpServletRequest`, `HttpServletResponse`,
   `ServletContext`, and all related types.
3. **Update your container.** Use Jetty 12+, Tomcat 11+, or deploy via
   Spring Boot 4.0+ / Quarkus 3.21+.
4. **Review synchronized code.** If you extended core Atmosphere classes
   that used `synchronized`, your subclasses may need corresponding
   `ReentrantLock` updates.
5. **Check deprecated API usage.** Methods and classes deprecated in 2.x
   and 3.x have been removed. Consult the Javadoc for replacements.

#### Client-side

1. **Remove jQuery.** atmosphere.js 5.0 has no jQuery dependency.
2. **Update imports.** The package is now `atmosphere.js` on npm. Use
   `import { atmosphere } from 'atmosphere.js'`.
3. **Review transport configuration.** The new client supports the same
   transports (WebSocket, SSE, long-polling, streaming) but the
   configuration API has been streamlined.

### Artifacts

| Module | GroupId | ArtifactId | Version |
|--------|---------|-----------|---------|
| Core runtime | `org.atmosphere` | `atmosphere-runtime` | `4.0.0` |
| Spring Boot starter | `org.atmosphere` | `atmosphere-spring-boot-starter` | `4.0.0` |
| Quarkus extension | `org.atmosphere` | `atmosphere-quarkus-extension` | `4.0.0` |
| AI streaming SPI | `org.atmosphere` | `atmosphere-ai` | `4.0.0` |
| Spring AI adapter | `org.atmosphere` | `atmosphere-spring-ai` | `4.0.0` |
| LangChain4j adapter | `org.atmosphere` | `atmosphere-langchain4j` | `4.0.0` |
| Embabel adapter | `org.atmosphere` | `atmosphere-embabel` | *not yet published (pending Embabel Maven Central release)* |
| MCP server | `org.atmosphere` | `atmosphere-mcp` | `4.0.0` |
| Kotlin DSL | `org.atmosphere` | `atmosphere-kotlin` | `4.0.0` |
| Redis clustering | `org.atmosphere` | `atmosphere-redis` | `4.0.0` |
| Kafka clustering | `org.atmosphere` | `atmosphere-kafka` | `4.0.0` |
| Durable sessions | `org.atmosphere` | `atmosphere-durable-sessions` | `4.0.0` |
| Durable sessions (SQLite) | `org.atmosphere` | `atmosphere-durable-sessions-sqlite` | `4.0.0` |
| Durable sessions (Redis) | `org.atmosphere` | `atmosphere-durable-sessions-redis` | `4.0.0` |
| TypeScript client | `atmosphere.js` (npm) | `atmosphere.js` | `5.0.0` |

### Compatibility Matrix

| Dependency | Minimum Version | Tested Up To |
|------------|----------------|--------------|
| JDK | 21 | 25 |
| Servlet API | 6.0 (Jakarta EE 10) | 6.1 |
| Spring Boot | 4.0.5 | 4.0.5 |
| Spring Framework | 6.2.8 | 6.2.8 |
| Quarkus | 3.21 | 3.31.3 |
| Jetty | 12.0 | 12.0.16 |
| Tomcat | 11.0 | 11.0.18 |
| Kotlin | 2.1 | 2.1+ |
| GraalVM (Spring Boot) | 25 | 25 |
| GraalVM / Mandrel (Quarkus) | 21 | 25 |

## Previous Releases

For changes in the 2.x and 3.x release lines, see the
[GitHub Releases](https://github.com/Atmosphere/atmosphere/releases) page
and the `atmosphere-2.6.x` branch.

[4.0.36]: https://github.com/Atmosphere/atmosphere/releases/tag/atmosphere-4.0.36
[4.0.11]: https://github.com/Atmosphere/atmosphere/releases/tag/atmosphere-4.0.11
[4.0.3]: https://github.com/Atmosphere/atmosphere/releases/tag/atmosphere-4.0.3
[4.0.0]: https://github.com/Atmosphere/atmosphere/releases/tag/atmosphere-4.0.0
