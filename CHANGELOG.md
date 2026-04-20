# Changelog

All notable changes to the Atmosphere Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — AI Agent Foundation v0.5 (eight primitives)

- **`AgentState` SPI** (`a0fd3fc48c`) — unifies conversation history,
  durable facts, daily notes, working memory, and hierarchical rules under
  one runtime-agnostic interface. File-backed default
  (`FileSystemAgentState`) reads and writes an OpenClaw-compatible
  Markdown workspace. `AutoMemoryStrategy` pluggable with four built-ins
  (`EveryNTurns`, `LlmDecided`, `SessionEnd`, `Hybrid`).
  `AgentStateConversationMemory` is a thin shim over the legacy
  `AiConversationMemory`. `AdkAgentRuntime` seeds its ADK `Session` from
  `context.history()` (closes Correctness Invariant #5 gap where
  `CONVERSATION_MEMORY` was advertised but silently dropped).
- **`AgentWorkspace` SPI** (`d4b3e341c7`) — agent-as-artifact. ServiceLoader
  discovery with `OpenClawWorkspaceAdapter` + `AtmosphereNativeWorkspaceAdapter`.
  OpenClaw canonical layout runs on Atmosphere without conversion.
- **`ProtocolBridge` SPI** (`853cccc4aa`) — `InMemoryProtocolBridge`
  elevated to first-class bridge, same footing as wire bridges.
  `ProtocolBridgeRegistry` enumerates active bridges.
- **`AiGateway` facade** (`4d48c3eb4a`, `43870cb537`) — single admission
  point for outbound LLM calls. `PerUserRateLimiter`, pluggable
  `CredentialResolver` and `GatewayTraceExporter`.
  `BuiltInAgentRuntime` now routes every dispatch through
  `AiGatewayHolder.get().admit(...)` — Correctness Invariant #3 enforced
  at the runtime boundary.
- **`AgentIdentity` SPI** (`f4df5603a7`) — per-user identity, permissions,
  credentials, audit, session sharing. `PermissionMode` layers over per-tool
  `@RequiresApproval`. `AtmosphereEncryptedCredentialStore` uses AES-GCM
  with a 256-bit key and per-entry random IV; decryption failure is
  fail-closed.
- **`ToolExtensibilityPoint` SPI** (`59f7ecd197`) — bounded tool discovery
  (`ToolIndex` + `DynamicToolSelector`) and pluggable per-user MCP trust
  (`McpTrustProvider` with `CredentialStoreBacked` default).
- **`Sandbox` SPI** (`818f531216`, `1e2daa1143`) — pluggable isolated
  execution. `DockerSandboxProvider` default + dev-only
  `InProcessSandboxProvider`. `@SandboxTool` annotation. Default limits
  1 CPU · 512 MB · 5 min · no network. `NetworkPolicy` enum
  (`NONE` / `GIT_ONLY` / `ALLOWLIST` / `FULL`) replaces the boolean
  network flag; Docker provider labels containers with the resolved
  policy.
- **`AgentResumeHandle` + `RunRegistry`** (`2ae4e8835b`, `27425b15f6`) —
  mid-stream reconnect primitive with bounded `RunEventReplayBuffer`.
  `StreamingSession.runId()` default method returns the id registered
  with `RunRegistry`; `DurableSessionInterceptor` stashes the
  `X-Atmosphere-Run-Id` header in a request attribute so the ai module
  can reattach without the durable-sessions module depending on
  atmosphere-ai.
- **Wire `ProtocolBridge` implementations** (`74d3ecbd6e`) for MCP, A2A,
  AG-UI, and gRPC so the admin control plane can answer "which agents
  are reachable via which protocol?" across every transport.

### Added — Two proof samples

- **`spring-boot-personal-assistant`** (`2a7ae59a41`) — primary
  coordinator delegates to scheduler / research / drafter crew via
  `InMemoryProtocolBridge`. Ships an OpenClaw-compatible workspace
  (`AGENTS.md` / `SOUL.md` / `USER.md` / `IDENTITY.md` / `MEMORY.md`)
  plus Atmosphere extension files (`CHANNELS.md` / `MCP.md` /
  `PERMISSIONS.md`).
- **`spring-boot-coding-agent`** (`ae9c2e174c`) — clones a repo into a
  Docker sandbox and reads files. `SandboxProvider` discovered via
  `ServiceLoader`; defaults to Docker, falls back to in-process for
  dev.

### Added — Security baseline

- **`ControlAuthorizer.DENY_ALL` and `REQUIRE_PRINCIPAL`** as explicit
  admin-plane baselines alongside the existing `ALLOW_ALL`. `ALLOW_ALL`
  is documented as non-production; operators wire
  `REQUIRE_PRINCIPAL` on top of their transport auth
  (Spring Security, Quarkus security) for production deployments.

### Added — Observability, grounded facts, guardrails, flow viewer

> Entries below land in one squash-merge commit on `main`; the hash
> will be stamped on the commit body when the merge happens.

- **`BusinessMetadata`** — standard keys (`business.tenant.id`,
  `business.customer.id`, `business.session.revenue`,
  `business.event.kind`, ...) with an `EventKind` enum. Published to
  SLF4J MDC on the dispatching virtual thread and cleared in
  `finally` so Dynatrace / Datadog / OTel log exporters propagate
  tenant + customer + revenue tags onto the active span for every
  agent turn.
- **`FactResolver`** SPI + `DefaultFactResolver` — injects
  deterministic facts (time, user identity, plan tier, custom
  `app.*` keys) into the system prompt before every turn. Resolution
  order matches `CoordinationJournal` / `AsyncSupport`:
  framework-property bridge (Spring beans) → `ServiceLoader` →
  process-wide holder → default. Newline / tab / control characters
  in values are escaped so fact values cannot reshape the
  instruction context.
- **`PiiRedactionGuardrail`** — regex-based detection of email,
  phone, credit card, US SSN, IPv4. Redacts on the request path,
  Blocks on the response path (the SPI cannot rewrite an
  already-emitted stream, so default-mode log-only signalling was
  security theatre).
- **`OutputLengthZScoreGuardrail`** — rolling-window drift detector;
  Blocks responses more than N standard deviations above the window
  mean. Opt-in via `atmosphere.ai.guardrails.drift.enabled=true`.
- **Agent-to-Agent Flow Viewer** — `GET /api/admin/flow` and
  `GET /api/admin/flow/{coordinationId}` render the
  `CoordinationJournal` as a graph (nodes = agents, edges = dispatch
  count + success / failure / avg-duration). Edge attribution is
  keyed per-`coordinationId` so concurrent tenant runs stay scoped.
- **Run reattach consumer** — `AiEndpointHandler` now reads the
  `X-Atmosphere-Run-Id` header on reconnection, looks up the
  live `AgentResumeHandle` via `RunRegistry`, and replays the
  buffered events onto the new resource. Closes the "producer
  present, consumer absent" gap in the original primitive wire-in.

### Fixed — Critical hardening

- **Admin HTTP writes now enforce authentication** in addition to
  the feature flag. `guardWrite(HttpServletRequest, action, target)`
  resolves a Principal from the servlet `UserPrincipal`, the
  Atmosphere `AuthInterceptor`-set attribute, or the `ai.userId`
  attribute, then consults `ControlAuthorizer`. The earlier
  feature-flag-only gate let any anonymous caller mutate state once
  the flag was flipped. Correctness Invariant #6 (Security).
- **MCP write tools forward the authenticated principal** to
  `ControlAuthorizer.authorize(...)`. Previously every tool passed
  `null`, so `REQUIRE_PRINCIPAL` permanently denied and `ALLOW_ALL`
  permanently admitted regardless of identity. New
  `IdentityAwareToolHandler` functional interface threads the
  servlet-resolved principal through `McpProtocolHandler.executeToolCall`.
- **`AiGateway` admission on cancel-capable dispatch paths**.
  `BuiltInAgentRuntime.doExecuteWithHandle` and
  `KoogAgentRuntime.executeWithHandle` now call
  `admitThroughGateway` — parity with the plain `execute` path so
  rate limits and credential policies fire on every mode
  (Correctness Invariant #7 — mode parity).
- **Business MDC lifecycle.** The MDC population was previously done
  on the servlet thread (wrong thread — VT logs never saw it) and
  never cleared. Snapshot on the servlet thread, apply on the VT
  dispatcher with try/finally clear, so every log record during the
  turn carries the tags and the VT pool starts clean on the next
  turn.
- **Flow graph attribution under interleaved coordinations.**
  `FlowController.buildGraph` previously carried a flat
  `currentCoordinator` cursor — concurrent runs misattributed every
  second edge. Now maintains a `coordinationId → coordinatorName`
  map.
- **User `@AiEndpoint` paths get Spring + ServiceLoader
  guardrails.** `AiEndpointProcessor` merges annotation-declared
  guardrails with `ServiceLoader.load(AiGuardrail.class)` and the
  framework-property bridge so annotation-declared endpoints are no
  longer starved of the auto-wired guardrail set. New
  `AiGuardrail.GUARDRAILS_PROPERTY` mirrors the
  `CoordinationJournal` bridge key.
- **Foundation E2E stops skipping the Docker sandbox regression.**
  `SKIP_SANDBOX_E2E=true` previously hid the command-injection
  hardening; removed from `foundation-e2e.yml` so the clone+read
  spec runs on every PR. ubuntu-latest ships with Docker — the new
  workflow also verifies its presence early.
- **Sample boot modernization.** `spring-boot-coding-agent` reverted
  from `application.properties` to `application.yml`; both samples
  add `spring-boot-starter-actuator`; `foundation-e2e.yml` boots via
  `./mvnw spring-boot:run` and waits on `/actuator/health` via
  `wait-on` instead of shelling out to `curl` and pre-building a
  fat jar.

### Fixed

- **`FileSystemAgentState` cross-scope bleed** (`ad850f9f35`).
  `MEMORY.md` and `memory/YYYY-MM-DD.md` now live under
  `users/<userId>/agents/<agentId>/` so facts never bleed across users
  or agents (Correctness Invariant #6, default deny on cross-scope
  access). Three new isolation tests cover cross-user, cross-agent, and
  cross-scope delete boundaries.

### Changed

- **`atmosphere new` is now sample-clone based** (`b7f98d42f0`, `0b9a8f194d`).
  The CLI no longer ships a mustache-based scaffold. `atmosphere new <name> --template <t>`
  now sparse-clones the matching sample from `cli/samples.json` and rewrites the
  cloned `pom.xml` so its `org.atmosphere:atmosphere-project` parent resolves from
  Maven Central (pins the version from SNAPSHOT to the release in `cli/samples.json`,
  drops the reactor-relative `<relativePath>`, disables repo-local checkstyle/pmd
  bindings). The resulting project compiles standalone with plain `mvn compile`.
- **Nine templates** in `cli/atmosphere` `cmd_new`: `chat`, `ai-chat`, `ai-tools`,
  `mcp-server`, `rag`, `agent`, `koog`, `multi-agent`, `classroom`. Each maps 1:1
  to a sample in `cli/samples.json`; `multi-agent` and `classroom` are new starters
  exposing the 5-agent A2A fleet and the AI-classroom Spring Boot + Expo RN sample
  respectively.
- **`create-atmosphere-app` (npx)** rewritten as a thin delegating shim
  (`944b190f43`). Drops the old JBang branch and the 240-line inline Java/HTML
  fallback, resolves the installed `atmosphere` CLI on PATH, and execs
  `atmosphere new <name> --template <t> [--skill-file <f>]`. Prints an actionable
  install hint if the CLI is missing. `TEMPLATES` list synchronized with the
  shell CLI's nine entries.

### Removed

- **`generator/AtmosphereInit.java`** + `AtmosphereInitTest.java` + `generator/templates/handler/**`
  + `generator/templates/frontend/**` + `generator/templates/{Application.java,application.yml,pom.xml}.mustache`
  + `generator/test-generator.sh` + `.github/workflows/generator-ci.yml` (`b7f98d42f0`).
  The JBang mustache scaffold is fully gone. `generator/ComposeGenerator.java` and
  its `generator/templates/compose/**` tree remain — they back the parametric
  skill-file driven multi-module scaffold invoked by `atmosphere compose`, which
  has no single-sample equivalent.
- **`cli/atmosphere` bash fallback tree** — `create_minimal_project`,
  `create_chat_handler`, `create_ai_chat_handler`, `create_agent_handler`,
  `create_index_html` (~430 lines). `cmd_new` now always clones; there is no
  fallback path.
- **`--group` flag on `atmosphere new` and `create-atmosphere-app`**. Samples
  ship with their own groupId; passing `--group` prints a deprecation warning
  and is ignored. Rename the groupId in `pom.xml` and `src/main/java` by hand
  after scaffolding if needed.

## [4.0.36] - 2026-04-13

Every bullet in this section is grounded in a real commit on `main` at the
time of release; commit hashes are listed where the attribution matters.

### Added

#### Seventh AI runtime

- **Microsoft Semantic Kernel adapter** (`atmosphere-semantic-kernel`).
  Seventh `AgentRuntime` implementation backed by Semantic Kernel's
  `ChatCompletionService`. Streams via the SK streaming chat API, honors
  system prompts, threads `AgentExecutionContext` into the SK invocation
  context, and reports token usage. Tool calling is deferred in 4.0.36
  (SK's Java `KernelFunction` tool binding is not yet bridged through
  `ToolExecutionHelper.executeWithApproval`). `SemanticKernelEmbeddingRuntime`
  ships alongside for embedding support via SK's
  `TextEmbeddingGenerationService`; blocks the reactive response at a 60s
  ceiling to avoid pinning a virtual thread on a hung service.

#### Unified `@Agent` API surface (Waves 1-6)

- **`ToolApprovalPolicy` sealed interface** (`c83469a478`). Four permitted
  implementations: `annotated()` (default, honors `@RequiresApproval`),
  `allowAll()` (trusted test fixtures), `denyAll()` (preview / shadow mode,
  no invocation ever runs), and `custom(Predicate<ToolDefinition>)` for
  runtime-dependent decisions. Attach via
  `AgentExecutionContext.withApprovalPolicy(...)`.
- **`ExecutionHandle` cooperative cancel** for in-flight executions via
  `AgentRuntime.executeWithHandle(context, session)`. Idempotent `cancel()`,
  terminal `whenDone()` future, `isDone()`. Runtime cancel primitives
  (verified from source):
  - Built-in: `HttpClient` request + SSE `InputStream.close()`
  - Spring AI: `reactor.core.Disposable.dispose()` on the streaming `Flux`
  - LangChain4j: `CompletableFuture.completeExceptionally` + `AtomicBoolean`
    soft-cancel flag consulted in the streaming response handler
  - Google ADK: `AdkEventAdapter.cancel()` →
    `io.reactivex.rxjava3.disposables.Disposable.dispose()` on the Runner
    subscription
  - JetBrains Koog: `AtomicReference<Job>` captured by `executeInternal` →
    `Job.cancel()` + virtual-thread `Thread.interrupt()` fallback +
    immediate `done.complete(null)` backstop
  - Semantic Kernel, Embabel: no-op sentinel (`ExecutionHandle.completed()`)
    — neither runtime overrides `executeWithHandle`, documented as a known
    gap.
- **`AgentLifecycleListener`** — observability SPI with `onStart`,
  `onToolCall`, `onToolResult`, `onCompletion`, `onError`. Attach via
  `AgentExecutionContext.withListeners(List<AgentLifecycleListener>)`.
  `AbstractAgentRuntime` fires start / completion / error via protected
  `fireStart` / `fireCompletion` / `fireError` helpers, so the five
  runtimes that extend it (Built-in, Spring AI, LC4j, ADK, SK) get
  lifecycle events automatically. Tool events fire through the static
  `fireToolCall` / `fireToolResult` dispatchers used by every tool bridge.
  Koog and Embabel implement `AgentRuntime` directly and do not yet fire
  start / completion / error (documented exclusion in
  `docs/reference/lifecycle-listener.md`).
- **`EmbeddingRuntime` SPI** with `float[] embed(String)`,
  `List<float[]> embedAll(List<String>)`, `int dimensions()`,
  `isAvailable()`, `name()`, `priority()`. Five implementations ship;
  service-loader resolution picks the highest-priority available one at
  runtime:
  - `SpringAiEmbeddingRuntime` — priority 200
  - `LangChain4jEmbeddingRuntime` — priority 190
  - `SemanticKernelEmbeddingRuntime` — priority 180
  - `EmbabelEmbeddingRuntime` — priority 170
  - `BuiltInEmbeddingRuntime` — priority 50 (zero-dep OpenAI-compatible
    fallback)
- **Per-request `RetryPolicy`** on `AgentExecutionContext`. Record shape:
  `(int maxRetries, Duration initialDelay, Duration maxDelay, double
  backoffMultiplier, Set<String> retryableErrors)`. Only the Built-in
  runtime currently honors the per-request override; framework runtimes
  inherit their own native retry layers and the capability is Built-in-only
  in 4.0.36 (per the pinned capability matrix in
  `AbstractAgentRuntimeContractTest.expectedCapabilities()`).
- **Pipeline-level `ResponseCache`** (`3e1fc6e4a7`). SHA-256 `CacheKey` over
  model, system prompt, message, response type, conversation history, tool
  names, and content parts (text / image mime+length / audio mime+length /
  file mime+length+bytes). Session-ID-independent so identical prompts hit
  the same cache line. `CacheHint` metadata on the context selects policy
  (`CONSERVATIVE`, `AGGRESSIVE`, `NONE`).
- **Multi-modal `Content`** — sealed `Content` type with `Text`, `Image`,
  `Audio`, `File` subtypes. Wire frames carry base64-encoded payloads with
  explicit `mimeType` and `contentType`. Runtimes that do not support
  multi-modal input declare the exclusion in their `capabilities()` set
  (Correctness Invariant #5 — Runtime Truth).
- **`session.toolCallDelta()` + `AiCapability.TOOL_CALL_DELTA`** — incremental
  tool-argument streaming so clients can render partial JSON as the model
  generates it. Declared as an `AiCapability` enum value so the distinction is
  machine-readable on the SPI, not just prose in the matrix. Only
  `BuiltInAgentRuntime` advertises it — its `OpenAiCompatibleClient` forwards
  every `delta.tool_calls[].function.arguments` fragment through
  `session.toolCallDelta(id, chunk)` on both the chat-completions and
  responses-API streaming paths. The six framework bridges (Spring AI, LC4j,
  ADK, Embabel, Koog, Semantic Kernel) cannot emit deltas without bypassing
  their high-level streaming APIs (`895a7e0a2e`); they honor the default
  no-op contract instead. Pinned in the Built-in contract test and in
  `modules/integration-tests/e2e/ai-tool-call-delta.spec.ts`'s negative
  capability assertion.
- **`AgentRuntime.models()`** default method returning the list of models
  the resolved runtime can actually serve. Replaces the configuration-intent
  model flag with a runtime-resolved list (Correctness Invariant #5).
- **`TokenUsage` record** `(long input, long output, long cachedInput,
  long total, String model)`. Reported on completion metadata as
  `ai.tokens.input` / `ai.tokens.output` / `ai.tokens.total` /
  `ai.tokens.cached` when the provider surfaces it.
- **`@AiEndpoint.promptCache()` and `@AiEndpoint.retry()`** — declarative
  annotations on `@AiEndpoint`. `promptCache()` returns
  `CacheHint.CachePolicy` (default `NONE`). `retry()` returns a nested
  `@Retry` annotation with `maxRetries`, `initialDelayMs`, `maxDelayMs`,
  `backoffMultiplier`. Resolved at bean post-processing on Spring Boot and
  via the annotation processor at build time on Quarkus.
- **`AbstractAgentRuntimeContractTest`** — TCK in `modules/ai-test` that
  every `AgentRuntime` subclass must pass. Exercises text streaming, tool
  calling, tool approval, system prompt, multi-modal input, cache hint
  threading, execution cancel, and capability-set pinning via
  `expectedCapabilities()` (added `c13e309d`) so adding or removing a
  capability from a runtime without updating its pinned set breaks the
  build. Drift between the code and the docs matrix in
  `tutorial/11-ai-adapters.md` cannot ship silently.

#### Unified `@Agent` + `@Command` (Wave 0-1, also in 4.0.36)

- **`@Agent` + `@Command`** — one annotation defines the agent; slash
  commands routed on every wired channel (Web WebSocket plus the five
  external channels when `atmosphere-channels` is present). Auto-generates
  `@AiEndpoint`, A2A Agent Card, MCP tool manifest, and AG-UI event bindings
  based on classpath detection.
- **`atmosphere-agent`** module — annotation processor, `CommandRouter`,
  `SkillFileParser`, `AgentHandler`.
- **`skill.md`** — markdown files that serve as both the LLM system prompt
  and agent metadata (`## Skills` / `## Tools` / `## Channels` /
  `## Guardrails` sections parsed into Agent Card, MCP manifest, channel
  validation).
- **JetBrains Koog adapter** (`atmosphere-koog`). Sixth `AgentRuntime`
  backed by Koog's `AIAgent` / `chatAgentStrategy()` with tool calling via
  `AtmosphereToolBridge` and cooperative cancel via `AtomicReference<Job>`.
- **Orchestration primitives** — agent handoffs
  (`session.handoff(target, message)`), approval gates (`@RequiresApproval`),
  conditional routing in `@Fleet`, and LLM-as-judge eval assertions
  (`LlmJudge`).
- **Samples**: `spring-boot-dentist-agent`,
  `spring-boot-orchestration-demo`, `spring-boot-checkpoint-agent` (durable
  HITL workflow surviving JVM restart via `SqliteCheckpointStore`).

### Fixed

Commit hashes listed for every Fixed bullet. If there is no commit, the
bullet does not belong here.

- **`ToolApprovalPolicy.DenyAll` bypass — P0 security** (`40d616b6ee`).
  `DenyAll.requiresApproval()` previously returned `true` and fell through
  to the session-scoped `ApprovalStrategy`, so an auto-approve strategy
  could silently run a tool the caller intended to deny.
  `ToolExecutionHelper.executeWithApproval` now detects `DenyAll` before
  consulting the strategy and returns
  `{"status":"cancelled","message":"Tool execution denied by policy"}`
  immediately. Closes Correctness Invariant #6 (fail-closed default).
- **Null-strategy approval bypass** (`56b1046f6f`). Before the DenyAll
  evaluation fix, a tool annotated `@RequiresApproval` running under a
  context with no `ApprovalStrategy` wired would execute unguarded. Now
  `ToolExecutionHelper` fails closed on null strategy; DenyAll is evaluated
  before the null-strategy branch. The same commit also stopped
  `CachingStreamingSession` from auto-persisting on `complete()` so a
  cancel-induced clean termination can no longer cache a partial response
  — the pipeline decides whether to commit after `runtime.execute` returns.
- **`ToolApprovalPolicy` not threaded through the tool-loop** (`b9b1af4aff`).
  Every runtime bridge previously called the 5-arg
  `executeWithApproval` overload which defaulted to
  `ToolApprovalPolicy.annotated()` — `context.approvalPolicy()` was never
  consumed. All five tool-calling bridges (Built-in, Spring AI, LC4j, ADK,
  Koog) now call the 6-arg form and pass the policy through.
  `ChatCompletionRequest` gained `approvalPolicy` as its 13th canonical
  field, preserved across tool-loop rounds.
- **`tryResolve` tri-state approval ID resolution** (`0db97e3276`,
  `c3cc904644`). `ApprovalRegistry.tryResolve(id)` returned `true` on
  `UNKNOWN_ID`, which caused `AiPipeline` / `AiStreamingSession` /
  `ChannelAiBridge` to swallow stale or cross-session approval messages as
  if consumed. Callers now use the tri-state `resolve()` method and only
  short-circuit on `RESOLVED`, letting `UNKNOWN_ID` fall through to the
  normal pipeline.
- **Koog cancel race** (`ae732f8301`). `KoogAgentRuntime.executeWithHandle`
  previously relied on a soft-cancel flag polled at suspension points, so a
  cancel racing with a slow Koog `PromptExecutor` stall could leave the
  virtual thread hanging on a native I/O read. The runtime now captures the
  active coroutine `Job` in an `AtomicReference`, cancels the job, and
  interrupts the virtual thread as a belt-and-suspenders fallback. The same
  commit enables ADK `ContextCacheConfig` bootstrap and fixes an
  `EmbeddingRuntimeResolver` startup-order race.
- **LC4j premature completion + ADK model override + cross-session approval
  fallback removal** (`d4c11ca76a`). LC4j's `doExecute` now blocks on
  `handle.whenDone()` before returning so the lifecycle completion fires
  after the tool stream actually finishes. ADK's `buildRequestRunner` now
  honors `context.model()` when set instead of falling through to the
  module-level default. `AiEndpointHandler` no longer performs a
  cross-session approval fallback, preserving session-ownership guarantees.
- **LC4j post-cancel error suppression + terminal-reason first-writer wins**
  (`4ca8e983d8`). LC4j now drops `onError` callbacks that arrive after the
  caller cancelled (the underlying HTTP may drain an IOException out of
  band). `ExecutionHandle.Settable` now records the first-writer terminal
  reason so observers can distinguish cancel from post-cancel error.
  `RetryPolicy.isInheritSentinel` formalises `DEFAULT`-as-inheritance
  contract.
- **`ResponseCache` observability gap + structured-output / RAG / guardrail
  cache-skip** (`28d381d4ff`). `CacheKey` now hashes `responseType` so a
  structured-JSON request cannot replay a plain-text cached answer.
  `AiPipeline` skips the cache when context providers, guardrails, or a
  latently non-empty tool registry are present. The cache-hit path now
  fires `AgentLifecycleListener.onStart` + `onCompletion` so observability
  and audit traffic see a clean pair on both hit and miss paths.
- **`CacheKey` Content.File collision + tool-loop cache skip**
  (`13cf557532`). `CacheKey` now hashes `Content.File` parts (mime + length,
  later upgraded to full byte hash in `c29542f1e6`) so two distinct PDFs of
  identical length cannot collide. `AiPipeline.streamText` skips the cache
  when `context.tools()` is non-empty so text-only replays never silently
  drop tool round-trips.
- **`CachingStreamingSession` binary-sendContent poisoning** (`0670e2f8b3`).
  Binary `Content` (Image / Audio / File) cannot ride through a text-only
  `StringBuilder`, so the default `sendContent` throw would have bypassed
  the text capture. Override now marks the session as errored so the
  pipeline's post-execute commit short-circuits — never caching a partial
  text-only response for a flow that emitted binary output.
- **Embedding runtime timeout + resolver DCL + cancel-swallow logging**
  (`c29542f1e6`). `SemanticKernelEmbeddingRuntime.block()` calls inherit a
  60s ceiling so a hung service cannot pin a virtual thread forever.
  `EmbeddingRuntimeResolver` wraps the slow path in a synchronized block so
  two early callers do not race duplicate ServiceLoader scans.
  `ExecutionHandle.Settable.cancel` logs native-cancel exceptions at TRACE
  instead of silently swallowing them, and documents the first-writer
  terminal-reason race explicitly.
- **Release automation stale-version patterns** (`a8516e9d7c`). Two gaps
  in `scripts/update-doc-versions.sh` left `README.md` "Current release"
  and `cli/sdkman/*.md` `publish.sh` examples pointing at the previous
  release after `release-4x.yml` ran. Both patterns are now swept on
  every release.
- **Cross-repo docs sync on release** (`dce1fba280`, `aadec4e1d8`).
  `release-4x.yml` now fires a `repository_dispatch` event at
  `Atmosphere/atmosphere.github.io` via the existing `SITE_DISPATCH_TOKEN`
  on successful release. A companion `sync-version.yml` workflow in the
  docs repo runs `scripts/update-doc-versions.sh` and commits the result.
  Closes the long-standing gap where the docs site lagged the Maven
  Central release by days.

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
