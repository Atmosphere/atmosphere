# Changelog

All notable changes to the Atmosphere Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed — A2A v1.0.0 alignment (wire-breaking)

- **`atmosphere-a2a` retracked to A2A v1.0.0** (`a2aproject/A2A@v1.0.0`,
  released 2026-03-12). The pre-1.0 wire surface was the slash-style
  method names (`message/send`, `tasks/get`, …) and a polymorphic
  `Part` envelope; both are gone in v1.0.0.
- **JSON-RPC method names switched to PascalCase** per spec §9.4 —
  `SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`,
  `CancelTask`, `SubscribeToTask`, the four
  `{Create,Get,List,Delete}TaskPushNotificationConfig` operations, and
  `GetExtendedAgentCard`. The pre-1.0 slash names and the old
  `tasks/pushNotification/*` path are aliased to their v1.0.0
  equivalents at handler entry, with a one-time WARN per legacy method
  seen — existing Atmosphere clients keep working through the
  transition.
- **HTTP+JSON / REST binding added** — colon-verb endpoints
  (`POST /tasks/{id}:cancel`, `POST /tasks/{id}:subscribe`,
  `POST /message:send` / `:stream`), `pushNotificationConfigs` CRUD
  URLs, and `GET /extendedAgentCard` are recognized by `A2aHandler`.
  REST requests are translated to JSON-RPC envelopes and dispatched
  through the same handler so the two bindings agree by construction
  (Mode Parity invariant #7).
- **Type schema rewrite under `org.atmosphere.a2a.types`**:
  - `Part` collapses three legacy subtypes (`TextPart` / `FilePart` /
    `DataPart`) into a single record carrying a `text | raw | url |
    data` oneof plus shared `metadata`, `filename`, `mediaType`. The
    deserializer continues to accept the pre-1.0
    `{"type":"text",…}` / `{"kind":"text",…}` envelopes for
    migration.
  - `Message.role` is now the `Role` enum (`ROLE_USER` /
    `ROLE_AGENT` per ADR-001 ProtoJSON). Lower-case legacy forms
    parse for back-compat.
  - `TaskState` adds `SUBMITTED` (the v1.0.0 ack-before-work state)
    and emits its proto-JSON name on the wire
    (`TASK_STATE_WORKING`, …).
  - `Task.messages` is renamed to `Task.history`; `TaskStatus` is
    promoted to a top-level type and carries a `timestamp`; both
    update events (`TaskStatusUpdateEvent`,
    `TaskArtifactUpdateEvent`) gain `contextId` and `metadata` and
    drop the redundant `final` flag.
  - `AgentCard` gains `supportedInterfaces` (so an agent can
    advertise JSON-RPC + HTTP+JSON at distinct URLs), structured
    `AgentProvider`, `AgentCardSignature`, `iconUrl`, structured
    `SecurityScheme`/`SecurityRequirement`, and `extendedAgentCard`
    moves into `AgentCapabilities` (was
    `supportsAuthenticatedExtendedCard`). The pre-1.0 top-level
    `guardrails` field is no longer modeled — guardrails surface as
    an `AgentExtension` on `AgentCapabilities.extensions` under
    `https://atmosphere.async-io.org/extensions/guardrails/v1`.
  - New types added for the missing v1.0.0 surface:
    `AgentInterface`, `AgentExtension`, `AgentSkill` (replaces
    `Skill`), `SecurityRequirement`, `SecurityScheme` +
    `APIKey`/`HTTPAuth`/`OAuth2`/`OpenIdConnect`/`MutualTls`,
    `OAuthFlows` + the three non-deprecated flow shapes,
    `AuthenticationInfo`, `TaskPushNotificationConfig`, and the
    response wrappers `SendMessageResponse`, `StreamResponse`,
    `ListTasksResponse`, `ListTaskPushNotificationConfigsResponse`.
- **Pagination + history honored**: `GetTask` reads
  `historyLength`; `ListTasks` reads `pageSize` (clamped 1..100,
  default 50), `pageToken`, optional `status` filter; the response
  is the v1.0.0 `ListTasksResponse` with `nextPageToken`,
  `pageSize`, `totalSize`. `SubscribeToTask` returns `-32004`
  `UnsupportedOperationError` on a terminal task.
- **Push-notification methods**: handlers route the four CRUD names
  but return `-32003` `PushNotificationNotSupportedError` —
  `AgentCapabilities.pushNotifications` is advertised as `false`
  (Runtime Truth invariant #5; deliveries are not yet wired so the
  capability flag stays honest).
- **SSE chunks** emitted by `A2aHandler.handleSseStreaming` are now
  spec-compliant `StreamResponse` envelopes carrying an
  `artifactUpdate` oneof variant (was a custom
  `{"artifact":{…}}` shape pre-1.0).
- Coordinator `A2aAgentTransport` updated to send/receive
  `SendMessage`/`SendMessageResponse` and to accept both the v1.0.0
  and pre-1.0 task-status shapes when classifying failure replies.
- `modules/agent` `AgentProcessor` updated to construct the v1.0.0
  `AgentCard` and surface guardrails via the extension URI above.
  Spring Boot `WellKnownAgentFilterTest` fixture rewritten for the
  new card constructor.

## [4.0.40] - 2026-04-24

### Added — Tool-call admission, per-request scope, audit sinks

- **Tool-call admission seam** (`1def61ddf0`) — `PolicyAdmissionGate.admitToolCall`
  builds a synthetic `AiRequest` whose metadata carries `tool_name`,
  `action`, and an argument preview so MS-schema rules over `tool_name`
  fire before the tool's executor runs. `ToolExecutionHelper` consults the
  gate on every `@AiTool` dispatch; the canonical MS example
  `{field: tool_name, operator: eq, value: delete_database, action: deny}`
  fires without operator plumbing. OWASP A02 upgraded from PARTIAL to
  COVERED.
- **`@AgentScope.postResponseCheck`** (`2913da1b81`) — when enabled on a
  high-stakes scope, `ScopePolicy` re-classifies the streamed response
  text against the declared purpose. OUT_OF_SCOPE responses become Deny
  with a `post-response:` prefix; errors fail-open on the response path
  (bytes already on the wire). `POLITE_REDIRECT` breaches downgrade to
  Deny because Transform can't rewind a stream.
- **Cross-provider governance contract** (`613d216019`) —
  `AbstractAgentRuntimeContractTest.policyDenyBlocksRuntimeExecute` is
  inherited by all seven runtime adapters (Built-in, Spring AI,
  LangChain4j, ADK, Embabel, Koog, Semantic Kernel); the "deny before
  runtime" guarantee is now a build-time invariant for each provider.
- **Per-request `ScopePolicy` install** (`334bde4969`) — an interceptor
  can write a `ScopeConfig` under `ScopePolicy.REQUEST_SCOPE_METADATA_KEY`
  and the pipeline / streaming session / admission gate install a
  transient `ScopePolicy` ahead of endpoint-level policies for that one
  turn. Classroom sample uses this for per-room scope (math / code /
  science / general) — one `@AiEndpoint` hosts four personas, each with
  its own purpose and forbidden-topic set. `perRequestScopeBlocksRuntimeExecute`
  extends the cross-provider contract to the per-request path.
- **Admin console governance views** — three Vue views under the existing
  Atmosphere Console (`/atmosphere/console/`) poll
  `/api/admin/governance/{policies,decisions,owasp}` on live intervals.
  Tabs auto-hide when governance is not installed. Verified end-to-end
  against the classroom sample via chrome-devtools (tabs render, OWASP
  matrix shows 7 Covered / 1 Partial / 1 Design / 1 Not-addressed, zero
  console errors).
- **Persistent `AuditSink` SPI** — `GovernanceDecisionLog.addSink(AuditSink)`
  fans every admission decision out to registered sinks while keeping
  the ring buffer authoritative for the admin console. Sink failures are
  isolated: one unreachable Kafka broker does not take down the pipeline.
  `AsyncAuditSink` wraps a blocking delegate with a bounded drop-on-full
  queue so the admission thread never blocks on IO (Backpressure
  invariant #3). Two reference modules ship: `atmosphere-ai-audit-kafka`
  (`KafkaAuditSink` → JSON to any topic) and `atmosphere-ai-audit-postgres`
  (`JdbcAuditSink` → JDBC upsert with schema auto-create, works against
  any JSR-221 `DataSource`; tests exercise H2 in-memory). The JSON shape
  matches MS Agent Governance Toolkit's `audit_entry` so downstream
  SIEM consumers of either system can read both.

### Added — Agent scope, audit trail, OWASP matrix

- **`@AgentScope` annotation + `ScopeGuardrail` SPI** (`ba7ddf3688`) —
  architectural goal-hijacking prevention. Annotation declares `purpose`,
  `forbiddenTopics`, `onBreach` (POLITE_REDIRECT / DENY / CUSTOM_MESSAGE),
  and `tier` (RULE_BASED / EMBEDDING_SIMILARITY default / LLM_CLASSIFIER
  opt-in). `ScopePolicy` maps `ScopeGuardrail` outcomes to
  admit/transform/deny semantics per the breach policy.
  `RuleBasedScopeGuardrail` ships with built-in hijacking probes for code,
  medical, legal, and financial patterns so the McDonald's failure mode
  is caught without operator-declared topic enumeration.
- **System-prompt hardening + endpoint auto-wiring** (`a11239cac3`) —
  `AiEndpointProcessor` reads `@AgentScope` on the endpoint class and
  auto-installs a `ScopePolicy` ahead of user-declared policies;
  `AiPipeline` prepends an unbypassable scope-confinement preamble to
  the system prompt on every `execute()` call, surviving sample-level
  substitutions.
- **Sample-hygiene CI lint + 12-sample retrofit** (`287a5f9b71`) —
  `SampleAgentScopeLintTest` walks `samples/` and fails the build on any
  `@AiEndpoint` missing `@AgentScope` (or lacking a non-blank
  `justification` when `unrestricted = true`). All 12 existing sample
  endpoints retrofitted — 11 declare `unrestricted = true` with specific
  justifications (production deployments replace with a scoped
  `@AgentScope`); `ReviewExtractor` declares a real scoped purpose.
- **Embedding-similarity scope tier** (`2c856bd00d`) — default tier.
  Resolves an `EmbeddingRuntime` via ServiceLoader; caches the purpose
  vector (and every forbidden-topic vector) on first use; rejects when
  cosine similarity falls below `similarityThreshold`. Absent runtime
  admits-with-warning so the rule-based tier remains a safe fallback.
- **LLM-classifier scope tier** (`5b8b6f51da`) — opt-in tier for
  high-stakes scopes. Zero-shot YES/NO classifier over the resolved
  `AgentRuntime`; tolerant parser handles `**YES**` / `YES.` / `*no*` /
  "Not sure" edge cases; ambiguous verdicts fall through to admit so
  LLM quirks don't over-reject; timeouts and runtime errors fail-closed
  at the `ScopePolicy` layer.
- **Governance audit trail** (`a534f5e462`) — `AuditEntry` records every
  `GovernancePolicy.evaluate` decision with identity, reason, context
  snapshot (redaction-safe: truncated message, primitive-only metadata),
  and `evaluation_ms`. `GovernanceDecisionLog` is a thread-safe ring
  buffer (default 500 entries) installed via
  `GovernanceDecisionLog.install(capacity)`. Surfaced via
  `GET /api/admin/governance/decisions?limit=N`. `GovernanceTracer`
  emits an OpenTelemetry span per evaluation through reflective
  classpath detection — OTel stays an optional dependency.
- **ms-governance-chat feature-parity retrofit** (`3fc8ead4cb`) — the
  sample now declares `@AgentScope(purpose = "Customer support for
  Example Corp — orders, billing, ...")` and loads 9 MS-schema rules
  mirroring MS's `customer-service/main.py` example (destructive SQL,
  legal/media/exec escalation, human-request auto-escalate, PII shapes,
  password disclosure, discount-limit enforcement, plus an `audit`-action
  rule for code-request probing).
- **OWASP Agentic Top-10 self-assessment matrix** (`712c57e4e8`) —
  `OwaspAgenticMatrix.MATRIX` pins 10 rows (6 COVERED, 2 PARTIAL,
  1 DESIGN, 1 NOT_ADDRESSED) with evidence classes, test references,
  and consumer grep patterns per row. `OwaspMatrixPinTest` fails the
  build when any evidence class is renamed or removed — structural
  answer to the v4 §4 discipline risk. Served over HTTP at
  `GET /api/admin/governance/owasp` for `agt verify`-style compliance
  consumers.

### Added — Governance policy plane

- **`GovernancePolicy` SPI** (`0ace2b6947`) — declarative policy identity
  (`name` / `source` / `version`) plus `PolicyContext`→`PolicyDecision`
  evaluation. Vocabulary aligned with OPA/Rego and Microsoft Agent
  Governance Toolkit at the evaluate-decision level (`admit` / `deny`
  / transform). The SPI is strictly additive — existing `AiGuardrail`
  wiring keeps working unchanged.
- **`GuardrailAsPolicy` + `PolicyAsGuardrail` adapters** (`efefaea40a`) —
  every existing `AiGuardrail` is reachable as a `GovernancePolicy` via
  `GuardrailAsPolicy`; policies land on the current `AiPipeline`
  admission seam via `PolicyAsGuardrail`. `Transform` decisions on the
  post-response path are downgraded to `pass` with a warning (streamed
  text is not retroactively rewritable).
- **YAML `PolicyParser` + `PolicyRegistry`** (`83e5c2dafd`) — default
  parser reads YAML via SnakeYAML's `SafeConstructor` (no arbitrary class
  instantiation). Built-in types: `pii-redaction`, `cost-ceiling`,
  `output-length-zscore` — factories wrap the shipped guardrails with
  the identity from the YAML entry. `modules/ai` declares SnakeYAML as
  an explicit runtime dep so bare-JVM / Jetty-embedded deployments get
  the parser out-of-the-box. Parser discovered via ServiceLoader;
  additional formats (Rego, Cedar) plug in by shipping another
  `PolicyParser` service entry.
- **Native `AiPipeline` wiring** (`9ac9ed1d6c`) — `AiPipeline` accepts a
  `List<GovernancePolicy>` on a new constructor, evaluates them in a
  dedicated pre-admission loop (fail-closed on exception per
  Correctness Invariant #2), and merges them onto the existing
  `GuardrailCapturingSession` for post-response via `PolicyAsGuardrail`.
  `pipeline.policies()` accessor exposes the installed list so admin
  surfaces can enumerate them without re-parsing YAML. Response cache
  skips when policies are present so each turn re-evaluates.
- **Spring + endpoint-processor bridge** (`9d7b75be78`) —
  `AiEndpointProcessor.instantiatePolicies()` merges ServiceLoader and
  `POLICIES_PROPERTY` sources with dedup by `name()`; policies are
  wrapped through `PolicyAsGuardrail` and threaded onto every
  `@AiEndpoint` in the app. `AtmosphereAiAutoConfiguration` now bridges
  Spring-managed `GovernancePolicy` beans onto the same property so the
  default path "drop a YAML file on the classpath" just works. Parity
  test (`PolicyPlaneSourceParityTest`) pins YAML / programmatic /
  ServiceLoader sources to identical admission decisions.
- **Classroom sample retrofit** (`aaac15f725`) —
  `samples/spring-boot-ai-classroom/src/main/resources/atmosphere-policies.yaml`
  declares a PII redaction and a z-score drift detector; `PoliciesConfig`
  reads it at startup and publishes the list to `POLICIES_PROPERTY`.
  Demonstrates the key promise: change YAML, restart, governance
  changes — zero code edits.
- **Admin introspection** (`b973aa2828`) — `GovernanceController` reads
  `POLICIES_PROPERTY` for per-policy identity and distinct-source
  counts; `/api/admin/governance/policies` and
  `/api/admin/governance/summary` HTTP endpoints expose the live list;
  `AtmosphereAdmin.overview()` reports the policy count alongside the
  AI runtime name.
- **Microsoft Agent Governance Toolkit YAML parity** — `YamlPolicyParser`
  auto-detects the MS schema (documents with top-level `rules:`) and
  produces a `MsAgentOsPolicy` that preserves MS's first-match-by-priority
  rule-evaluation semantic. All nine comparison operators (`eq`, `ne`,
  `gt`, `lt`, `gte`, `lte`, `in`, `contains`, `matches`) and all four
  actions (`allow`, `deny`, `audit`, `block`) are honored. Context map
  bridges `AiRequest` fields (`message`, `model`, `user_id`, …) and every
  metadata entry to rule field names. `MsAgentOsYamlConformanceTest`
  loads MS's own example YAMLs (copied unmodified from `microsoft/
  agent-governance-toolkit@April-2026`) and asserts byte-for-byte
  interop.

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

### Added — Observability, grounded facts, guardrails, flow viewer (`1df67cdd7d`)

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

### Added — Stream-level PII rewriting

- **`PiiRedactionFilter`** (`c2076a41a1`) — `BroadcasterFilter` that
  rewrites email / phone / credit-card / US SSN / IPv4 tokens in-flight
  before bytes reach the client. Atmosphere owns the broadcaster, so
  rewriting happens on the same thread that would have flushed the
  unfiltered bytes — a pure orchestration layer can only block a
  streaming response, never redact it mid-flight. Auto-installs on
  every present and future broadcaster when
  `atmosphere.ai.guardrails.pii.enabled=true`. Replacement token
  configurable via `atmosphere.ai.guardrails.pii.replacement`
  (default `[REDACTED]`). Listener ownership is symmetric — a
  `DisposableBean` removes the installed listener on shutdown.

### Added — Observability → enforcement (cost ceiling wire)

- **`CostAccountant` SPI + `CostCeilingAccountant` impl**
  (`1e06de99bb`) — bridges `TokenUsage` events from any runtime into
  `CostCeilingGuardrail.addCost(tenantId, dollars)`. Installed
  automatically when both a `CostCeilingGuardrail` bean and a
  `TokenPricing` bean are present on the Spring Boot classpath.
  Closes the "observability as dashboard" gap: cumulative tenant
  cost now gates outbound dispatch instead of only surfacing on a
  Grafana panel.
- **`TokenPricing` SPI** — per-model dollar-per-token schedule.
  Applications supply their own pricing (provider quotes change);
  no baked-in table.
- **`CostAccountingSession` decorator** — wraps every `@Prompt`
  session in `AiStreamingSession.dispatch` whenever the
  `CostAccountantHolder` is non-NOOP. Captures `TokenUsage` from
  every runtime via the existing `StreamingSession.usage(...)` hook,
  routes the cost through the accountant, then forwards to the
  delegate so the runtime call path is unchanged. Tenant MDC is
  snapshotted at construction so Reactor-thread usage events don't
  collapse into the `__default__` bucket.
- **`CostCeilingGuardrail`** (`c2076a41a1`) — inspects requests
  before dispatch; blocks outbound `@Prompt` when the tenant's
  cumulative cost is at or above the per-tenant budget. Per-tenant
  buckets keyed by `business.tenant.id` MDC; turns without a tenant
  tag share a `__default__` bucket. `resetTenant(...)` / `resetAll()`
  on an operator-driven schedule for monthly billing boundaries.

### Added — Tenant-partitioned drift

- **`OutputLengthZScoreGuardrail` tenant partition**
  (`c2076a41a1`) — the rolling-window drift detector now partitions
  its window by `business.tenant.id`, so a noisy tenant can no
  longer poison other tenants' baselines. Single-tenant deployments
  fall back to a shared `__default__` bucket and behave unchanged.

### Added — Reattach wire closure

- **`RunReattachSupport`** (`c2076a41a1`) — stateless helper
  extracted from `AiEndpointHandler.reattachPendingRun`. Replay
  writes the joined buffer directly to `response.getWriter()`
  (U+001E between events) on reconnection. Unit-tested standalone.
- **`RunEventCapturingSession` producer wire** (`8156842fd4`) —
  closes the "consumer wired, producer missing" half of the first
  reattach landing. Mirrors every `session.send` / `complete` /
  `error` into the run's `RunEventReplayBuffer` so reconnecting
  clients have something to replay.
- **Reattach wire fidelity** (`69d5dad403`) — replay emits
  `AiStreamMessage` JSON frames matching the live-stream schema
  (one parser, two paths); `AiEndpointHandler` routes
  timeout / exception terminals through the capturing session so
  replayed streams end with a proper error envelope.
- **P0 reattach ownership + filter-chain replay** (`ffedda4b7e`) —
  replay refuses when the reconnecting caller's resolved `userId`
  does not match the run's registered `userId` (closes a
  bearer-token cross-user leak). Anonymous runs keep the open-mode
  carve-out so demo deployments still work. Every replay frame is
  routed through the broadcaster's `BroadcastFilter` chain so
  `PiiRedactionFilter` and downstream content filters apply
  identically to replay and live frames — a direct-writer path
  previously bypassed them.
- **`RunEventCapturingSession.handoff()` forwarding**
  (`ff8c2d5542`) — the default `StreamingSession.handoff` throws
  `UnsupportedOperationException`; the capturing wrapper inherited
  it and broke orchestration-primitives handoffs. Now delegates.

### Added — Admin read-side auth gate

- **`atmosphere.admin.http-read-auth-required` opt-in flag**
  (`2d3ee5afc3`) — when true, `GET` / `HEAD` / `OPTIONS` on
  `/api/admin/*` require the same principal chain as the write-side
  gate (minus `ControlAuthorizer`). Default off so local demo
  consoles keep working; multi-tenant operators exposing
  `/api/admin/*` on a routable network flip one flag.
- **`AdminApiAuthFilter`** (Spring) + **`AdminReadAuthFilter`**
  (Quarkus JAX-RS `@Provider`) — symmetric enforcement across
  starters.

### Added — Quarkus admin parity

- **Fourth principal source on Quarkus admin writes**
  (`b3d032d00c`) — `X-Atmosphere-Auth` header validated via
  constant-time compare against `atmosphere.admin.auth.token`. A
  synthetic principal is admitted on match. Intended for sample
  fixtures and operator tooling that have not yet integrated
  Jakarta Security; production stacks still resolve via
  `SecurityContext.getUserPrincipal()` first.
- **Malformed journal timestamp returns 400** (`9aa1651f3f`) —
  previously 200 with an error-item array, which masked client
  errors and broke Spring / Quarkus API parity. Now matches the
  Spring `AtmosphereAdminEndpoint` behavior (Correctness
  Invariant #4).

### Added — Favicon service

- **`AtmosphereFaviconAutoConfiguration`** (`98c6ae408b`,
  `2d3ee5afc3`) — serves `/favicon.ico` and `/favicon.png` with the
  Atmosphere logo PNG on every app using either spring-boot starter,
  killing the default 404 on the admin UI, console UI, and every
  sample. Opt out with `atmosphere.favicon.enabled=false`. Ships
  as a nested `@RestController` inside the `@AutoConfiguration`
  class; a `@Bean` factory introduced in the initial commit
  produced a duplicate `atmosphereFaviconController` bean and
  triggered `Ambiguous mapping` at startup — removed in the fix.

### Added — Admin Flow tab UI

- **SVG coordination-graph visualizer** (`d1245d7780`) — new admin
  console tab renders `/api/admin/flow` as a circle-layout SVG.
  Nodes are agents, edges carry dispatch count / success / failure /
  average duration (red on failure, arrowheads for direction).
  Optional `coordination-id` drilldown and `lookback-minutes` filter.
  Zero external graph library. Mirrored across
  `spring-boot-starter` and `spring-boot3-starter` admin assets.

### Added — Correctness coverage

- **`RuntimeGatewayAdmissionParityTest`** (`d5f8a03174`,
  `81c135cf5c`, `1632360f7f`) — source-level parity scan with
  brace-balanced method-body extraction; every
  `*AgentRuntime.{java,kt}` must call `admitThroughGateway` from
  its designated dispatch methods or the build fails. Catches a
  regression where a runtime's dispatch path silently bypasses
  rate limiting and credential policy.
- **Seven exec-level `*GatewayAdmissionTest` files** —
  `SpringAiGatewayAdmissionTest` (`48a38d58c6`); LangChain4j, ADK,
  Semantic Kernel (Java) and Koog, Embabel (Kotlin) together
  (`1006a4301d`); plus the existing
  `BuiltInExecuteWithHandleGatewayTest`. Each installs a counting
  `AiGateway.GatewayTraceExporter` and drives `runtime.execute(...)`
  so an admission entry with the correct provider label is captured
  at the exec level, not just source-level grep.
- **`ChangelogClaimsTest`** (`94404ce023`) — pins the
  `AgentState` OpenClaw workspace layout and `RunEventReplayBuffer`
  bound so CHANGELOG-to-code drift breaks the build.

### Added — Reattach e2e harness

- **`samples/spring-boot-reattach-harness`** (`65f2f6ce5f`) —
  `SlowEmitterChat` plus a `SyntheticRunController` that
  pre-populates the `RunRegistry` so Playwright can drive the
  `HTTP → reattachPendingRun → replayPendingRun` wire with
  deterministic timing. `e2e/tests/reattach.spec.ts` runs on every
  push via a dedicated `foundation-e2e.yml` job on port 8096 — the
  reattach contract is proven end-to-end, not just at unit level.

### Added — Performance baseline

- **`BusinessMdcBenchmark`** (`82899e5145`) — JMH harness pinning
  the cost of the per-turn `business.*` MDC snapshot → apply → clear
  cycle that `AiEndpointHandler.invokePrompt` runs on every
  dispatch. Baseline, six-key production, and empty-snapshot
  scenarios, so a regression on the hot path shows as numbers, not
  intuition.

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

### Fixed — Security dependency baseline

- **Jetty 12.0.33, Tomcat 11.0.21, Kafka 3.9.2** (`8e4b63e18a`) —
  closes 13 Dependabot advisories (1 critical, 5 high, 3 medium).
- **Bouncy Castle 1.84** pinned in `dependencyManagement`
  (`246c29bc75`) — closes LDAP-injection and risky-crypto
  advisories against the 1.82 tree that `docker-java-core 3.7.0`
  pulls in transitively. Provided-scope only (Docker sandbox path);
  no runtime fat-jar drift.
- **protobuf 4.34.1** pinned to match `protoc` (`4876779978`) —
  `grpc-protobuf 1.80.0` still pulled `protobuf-java 3.25.8`
  transitively, so `protoc 4.x`-generated sources failed to compile.
- **MCP SDK 1.0.0 → 1.1.1** (`da57094ce6`).
- **React / React DOM 19.2.5 in lockstep** (`8226e28fcb`) — React
  requires an exact version match between `react` and `react-dom`;
  a partial Dependabot bump broke every jsdom-backed test with
  `ensureCorrectIsomorphicReactVersion`.
- **`HtmlEncoder` registered as a CodeQL XSS sanitizer**
  (`b5f184e417`) — resolves four false-positive `java/xss`
  code-scanning alerts.

### Fixed — Quarkus resteasy-reactive Vert.x dispatch

- **`AdminResource` survives `IllegalStateException: UT000048`**
  (`e2d254ad68`) — resteasy-reactive dispatches on Vert.x, so
  `@Context HttpServletRequest` attribute access throws on the admin
  write path. Attribute access is swallowed (attributes cannot fire
  on Vert.x anyway) and `X-Atmosphere-Auth` is read via
  `@Context HttpHeaders`, which works on both transports.

### Fixed — JDK 26 integration test timing

- **`GrpcWasyncTransportTest` status-poll 2s → 5s**
  (`1586d91246`) — wAsync updates `Socket.status()` on its dispatch
  thread after the `CLOSE` callback returns; the 2s polling cap was
  too tight on JDK 26 where scheduler latency between callback and
  CAS is observably longer.

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
