# Changelog

All notable changes to the Atmosphere Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.0.62] - 2026-07-14

### Added

- session-tape replay — reconstruct a run and a multi-agent coordination tree TapeReplay reconstruct/reconstructTree (no model) + parentRunId linkage (withParentRun→A2A metadata) + tool-agent dispatch tape; gated /api/admin/tape/runs/{id}/replay + Console Replay tab; fixes A2A metadata-inherit and Resilient/Intercepting proxy propagation; docs (tutorial 36 + reference/tape + READMEs)
- Tape tab — browse recorded session tapes in the Atmosphere Console Adds gated /api/admin/tape read endpoints (runs + steps, content-read-auth per Inv#6) + hasTape runtime-truth flag + a Vue Tape tab (runs list + step detail) in the spring-boot-starter console
- tape → training data — self-contained tape + TapeTrainingExtractor Record the input prompt as a tape step on both dispatch paths so the tape is a (prompt→completion) record; TapeTrainingExtractor folds runs to chat-JSONL, TapeDatasetCli emits it; spring-boot-ai-chat demos the durable tape
- session tape — durable typed AiEvent stream, resume, admin read Records the AiEndpointHandler + AiPipeline session seams to SQLite/in-memory TapeStore; late-bound runId, crash-resume segments, atmosphere_read_tape gated REQUIRE_PRINCIPAL; opt-in (atmosphere.ai.tape.enabled) across both Spring starters + Quarkus
- opt-in security-headers filter for app routes; mcp-server consumes it
- in-process sandboxed eval tool (Rhino) — deepagents eval parity Container-free 'eval' JS tool: sandboxed Rhino scope (deny-all ClassShutter, no java/Packages bridge, interpreted-mode instruction + wall-clock budget, fresh scope per call), default-off and runtime-truth gated via an optional dependency, registered at the shared agent/endpoint tool site. Closes the last deepagents capability gap; 14 tests pin the sandbox.
- add delete + rename file tools — deepagents file-tool parity The built-in filesystem floor grows from six to eight tools: delete (ToolKind.DELETE, approval-gateable) and rename, both bounds/traversal-guarded over the conversation-scoped AgentFileSystem, closing the delete gap vs LangChain deepagents. Updates every count/phrasing across code, tests, READMEs (six→eight), and the e2e tool-count pins (dentist + orchestration now log tools: 11 = 2 user + 9 harness) — both verified empirically from a booted sample.
- wire governance decision log to Postgres/Kafka audit sinks Config-gated auto-config installs JdbcAuditSink/KafkaAuditSink so admit+deny persist
- thread inbound image input onto the model request (vision)
- discover Cedar/Rego PolicyParser SPIs on the governance reload path
- wire configurable backpressure drop-policy into the broadcaster write path
- install path for the 5 missing governance admission stages PERMISSIONS.md now wires all 7 stages (rate-limit, concurrency, message-length, time-window, kill-switch)
- one-dependency streaming @Agent chat sample proving the blog claim Add spring-boot-one-dep-agent; make atmosphere-ai-spring-boot-starter pull the web server non-optionally so one dependency boots a running streaming chat app.
- add Checkpoints tab to admin Console over durable-run store

### Fixed

- isolate optional tape types from always-active admin/console signatures Direct atmosphere-ai tape refs were force-loaded by Spring getDeclaredMethods, crashing non-AI samples at startup; reads move to TapeAdminSupport + reflective hasTape probe
- protect WEB-INF/META-INF in embedded-jetty chat sample
- honor SERVER_PORT env in kotlin-dsl-chat + embedded-jetty-websocket-chat
- nonce-based strict CSP for the console + harden mcp-server root SPA
- harden CORS credentials, console headers, A2A JSON charset, error disclosure
- map spring-boot-one-dep-agent into the release-gate smoke shard Unmapped sample tripped the no-silent-caps gate; smoke tier (boot jar + GET /atmosphere/console/ == 200) verified locally
- keep ai-chat content-read secure by default; opt out only in the real-LLM demo run
- ms-governance-chat opts out content-read-auth so its Decisions tab renders token-less
- reject SSRF to internal targets in push-notification webhooks Deny loopback/private/link-local/metadata by default (delivery-time re-check for DNS rebinding); opt in via org.atmosphere.a2a.pushAllowPrivateTargets on trusted networks
- ai-chat opts out content-read-auth so the console Decisions tab renders token-less
- auth the governance/decisions poll (read-plane fail-close regression)
- bump rhino 1.7.15 to 1.7.15.1 (CVE-2025-66453)
- pin logback 1.5.37 in sample poms (CVE-2026-10532) samples re-import the Spring Boot BOM (logback 1.5.34); a direct version pin forces the patched line
- bump logback 1.5.33 to 1.5.37 (CVE-2026-10532 object injection)
- patch DoS vulns in test deps + pin org.json (Dependabot) ws 8.19→8.21 (CVE-2026-48779), @grpc/grpc-js 1.14.3→1.14.4 (GHSA-5375-pq7m-f5r2), org.json pinned 20250517
- add justified unrestricted @AgentScope to one-dep ChatAgent
- require auth for recorded-content admin reads by default governance/decisions, audit, journal expose prompt/response + coordination content — default-deny, Spring+Quarkus parity, opt-out content-read-auth-required
- sync capability skillcards + README to the 22-capability set

### Changed

- make compose-with-native-frameworks layering explicit across READMEs
- tempest campaign coverage overstated — ~8/32 genuinely tested, not 21
- document the unauthenticated channel posture and production hardening
- log drift — swept setup-java hardening on a stale local tree Enumerated verify-signature targets before rebasing onto origin/main; gate is fetch+rebase then re-enumerate before any push-bound sweep.
- GPG-verify Temurin JDK archives across all setup-java steps Fan out the dependency-submission verify-signature pilot; Oracle JDK 26 matrix leg gated off, graalvm skipped (both unsupported).
- run the coverage-map gate on push/PR, not just nightly A new samples/ dir missing its release-gate map entry now fails the introducing PR (build-free --list check) instead of the next nightly run
- bump the maven group across 26 directories with 1 update (#2712)
- bump testcontainers.version from 1.20.6 to 1.21.4 (#2702)
- bump quarkus-langchain4j.version from 1.9.2 to 1.11.2 (#2704)
- bump tools.jackson.core:jackson-databind (#2703)
- bump the sample-frontend group across 15 directories with 2 updates (#2707)
- bump actions/github-script from 7 to 9 (#2706)
- bump codecov/codecov-action from 6 to 7 (#2705)
- pilot verify-signature on the dependency-submission lane
- shared sample config change to fix one e2e broke another on the same fixture
- admin read-plane fix verified via edited specs, not the console consumer it broke
- lead with streaming again — demote deep-agent row, tighten hero Deep-agent Why row moves below streaming/adapters; LangChain deepagents comparison stays only in the harness subsection.
- same-session recurrence — read -q dependency:tree empty result as dependency absent
- make the eval interpreter pluggable via the EvalEngine SPI Extracts a ServiceLoader EvalEngine SPI (highest-priority-available wins, like AgentRuntime); Rhino/JavaScript ships as the default at priority 0, alternatives plug in by adding a jar. Rhino internals move to RhinoScriptSandbox so the adapter signature stays optional-dep-clean. 5 tests pin priority-override + availability-gating.
- wrong 'qwen too weak' call from 0 findings without checking the LLM was reached
- click-through demos for tool-output offload + composite FS routing personal-assistant README now has reproducible offload (LLM_TOOL_OUTPUT_OFFLOAD_THRESHOLD=200 → read_file result offloads to tool-output/) and composite-routing (LLM_FILESYSTEM_ROUTES=memory/=dir → prefix lands in durable backend, rest stays per-conversation) walkthroughs — both browser+disk-proven on Ollama, matching what a user will see in the Workspace tab.
- showcase the task tool, offload, and composite FS routing personal-assistant README documents the dynamic subagent-spawn task tool (the @Coordinator harness registers it alongside delegate_task) with a copy-paste Ollama flow — browser-proven end to end; coding-agent README adds tool-output disk offload (default-on) and composite filesystem routing (atmosphere.ai.filesystem.routes) so users can run the newest deep-agent features, not just read about them.
- allowlist java.sql.DriverManager Javadoc ref in the audit DataSource
- drop redundant MULTI_AGENT_HANDOFF, wire Koog MODEL_ENUMERATION
- restore broadcaster tagline, surface deep-agent harness + MCP/A2A/AG-UI
- false 'pinned version' fact in Embabel comment shipped in 4.0.60/4.0.61
- bump version to 4.0.61
- prepare next development version 5.0.37
- prepare for next development iteration 4.0.62-SNAPSHOT

## [4.0.61] - 2026-07-07

### Added

- governance as a learning signal — Prefer decision, feedback loop, durable recall PolicyDecision.Prefer soft-preference + native preference type + GovernanceFeedbackInterceptor (re-injects deny/prefer guidance into the turn) + opt-in durable provenance memory across Spring/Quarkus/bare-JVM; real-LLM sample (spring-boot-ai-chat) + Ollama e2e; console PREFER view; real-LLM CI consolidated on Ollama.
- tool-output disk offload + composite AgentFileSystem routing Large built-in tool results (>8000 chars, threshold-gated, sysprop/env overridable) spill to the agent workspace and the model gets a preview + read_file pointer — default-on at the tool choke point, fail-safe (never throws/loses data, honors workspace bounds). AgentFileSystemProvider composes a CompositeAgentFileSystem over the per-conversation workspace when durable prefix routes are configured (atmosphere.ai.filesystem.routes, off by default), routing the eight ops by longest-prefix with per-route bounds. Closes the two minor deepagents parity gaps. 3+15+2 tests.
- task tool — dynamic ephemeral subagent spawn (deepagents parity) The @Coordinator harness now registers a 'task' tool alongside delegate_task: it spawns a general-purpose subagent with an isolated context/workspace (fresh conversation, own plan store + bounded file store), runs one subtask with the harness floor, and returns its final report. Governed (pre-admission, fail-closed), depth-bounded across the spawn thread, time-bounded with cleanup; 7 tests + preset pin. Closes the last parity gap vs LangChain deepagents.
- native tool-loop enforcement via Embabel 0.5.0 inspector/transformer API The stale comment claimed 0.3.5 lacked the seam; 0.5.0 ships withToolLoopInspectors/withToolLoopTransformers, so EmbabelToolLoopBridge strips tool calls on the transformer seam at the cap (the real stop) and mirrors ToolLoopGuard's breach on FAIL, honoring COMPLETE_WITHOUT_TOOLS natively; wire guard kept as backstop, native path only, 7 tests.

### Fixed

- env/sysprop LLM knobs win over RUNTIME.md pins A workspace RUNTIME.md model/mode/base-url/api-key pin was overriding an operator's explicit LLM_MODEL/LLM_MODE/... env (or system property), so pointing the personal-assistant sample at Ollama 404'd on the pinned gemini model; pins are now defaults an env override beats. Regression + env-lookup seam keep the test hermetic against ambient .envrc vars.

### Changed

- log drift — feature called 'documented' while published docs were stale Governance learning-signal PolicyDecision.Prefer shipped but atmosphere.github.io reference listed only Admit/Transform/Deny; caught on the completion-number question, external docs now published.
- surface the batteries-included deep-agent harness A plain @Agent is a deep agent out of the box (memory, write_todos plan, virtual filesystem, task sub-agent spawn) — add a Why-Atmosphere row and a dedicated subsection linking the harness docs and the LangChain deepagents comparison.
- note governance learning-signal loop in module README Points at governance-policy-plane.md + the spring-boot-ai-chat sample; adds the native prefer vocabulary + durable-recall flag.
- rewrite compliance matrix notes in plain operator language EU AI Act / HIPAA / SOC2 rows; class names and config move to the evidence disclosure
- deep-agent harness primitives on a @Coordinator Deterministic Playwright spec (demo mode, no live LLM) pinning that the personal-assistant coordinator registers the dynamic subagent-spawn task tool + delegate_task + the write_todos floor, and /api/console/info reports planning/filesystem/delegation ACTIVE — the wiring a real turn depends on, guarded in the always-on lane.
- rewrite OWASP matrix notes in plain operator language class names, config keys and roadmap labels move to the evidence disclosure
- bump version to 4.0.60
- prepare next development version 5.0.36
- prepare for next development iteration 4.0.61-SNAPSHOT

## [4.0.60] - 2026-07-06

### Added

- native plan bridges persist to AgentPlanStore under the floor's key Embabel GOAP and Koog planner mirrors now also put() every plan under the same (agentId, conversationId) the write_todos floor uses, so the admin plan endpoint and Workspace stored view work for opted-in native surfaces; best-effort with WARN on store failure, pinned by persistence + failure-isolation tests in both bridges.
- koog native plan observation, embabel bounded file tools, plan-event correlation KoogPlanner dispatch branch + KoogPlanBridge mirror planner lifecycle into PlanUpdate (capability deliberately undeclared: koog plans exist only when the caller supplies a planner, so the write_todos floor must stay); AtmosphereFileTools implements Embabel's FileTools over the bounded conversation store (VIRTUAL_FILESYSTEM declared); PlanUpdate now carries conversationId/agentId at every emitter so the console Workspace tab correlates live plans to stored state one-click.
- planning + virtual-filesystem harness primitives; console broadcast fix write_todos and bounded ls/read/write/edit/glob/grep tools attach by default on every tool-calling runtime (Harness PLANNING/FILESYSTEM); AgentScope PlanNotebook, Embabel GOAP and Alibaba todos delegate via AiCapability.PLANNING while ADK artifacts and the Anthropic memory tool expose the store via VIRTUAL_FILESYSTEM; console gains a Workspace tab plus a broadcast-mode fix from a 29-sample browser sweep (outbound {author,message} envelope, event-less inbound frames render, cross-client Playwright regression); browser-proven with a real model.
- Harness feature set on @Agent/@Coordinator/@AiEndpoint replaces the deep-agent boolean @Agent and @Coordinator are batteries-included by default (harness() = {ALL}; empty array opts down to a bare loop) while a bare @AiEndpoint opts in per endpoint; the app-wide atmosphere.ai.harness.enabled flag is tri-state with explicit false as a kill switch beating every annotation; user-facing config keys and the console runtime-truth block rename deep-agent to harness; HTTP + Playwright e2e pin default-on, opt-in and kill-switch on booted apps.
- deep-agent harness on @Agent(deepAgent) + wired @SandboxTool @Agent(deepAgent=true) — or atmosphere.ai.deep-agent.enabled app-wide — attaches long-term memory, a prompt-cache default, selectable compaction and fleet delegation with per-primitive runtime-truth at /api/console/info; @SandboxTool routes a tool method through a framework-owned Sandbox; fixed the built-in runtime model fallback so LTM fact extraction works (browser-proven cross-session recall); Spring/Quarkus/servlet parity; personal-assistant + coding-agent samples converted.
- @Command slash commands work on @AiEndpoint, not only @Agent New AiHandlerDecorator SPI lets atmosphere-agent wrap any @AiEndpoint that declares @Command methods with the same command router the @Agent path uses; rag-chat console /sources is now an instant KB listing instead of falling through to the LLM.
- honor @AgentScope on @Agent classes and lint their scope posture
- client-reachable durable crash-resume via X-Atmosphere-Run-Id reconnect
- lead quarkus-ai-chat with @Agent instead of @AiEndpoint
- lead spring-boot-ai-chat with @Agent instead of @AiEndpoint
- lead spring-boot-channels-chat with @Agent instead of @AiEndpoint
- add atmosphere-ai-spring-boot-starter for one-dependency @Agent apps
- durable agent runs with deterministic replay across runtimes
- auto-wire default CheckpointStore and ContextProvider beans in-memory defaults via @ConditionalOnMissingBean with startup warnings; all six families now self-wire
- prove atmosphere-admin-bundle self-wires the six families
- add kotlin-dsl-chat proving the Kotlin DSL + coroutine extensions
- prove Spring AI setChatClient + defaultAdvisors + per-request advisors
- deliver audio input to the runtime via stream(message, parts) MultiModalChat forwards audio: prompts as Content.Audio input; delivery test asserts the runtime context carries the audio part
- add @AiEndpoint(broadcastReply) to fan one reply out to a room
- add passivation-agent sample proving pause/resume Snapshots a paused conversation to a CheckpointStore and resumes it from the restored history; PassivationDeliveryTest asserts the round-trip.
- gate a money-moving refund tool behind @RequiresApproval
- enforce @Agent scope from skill ## Guardrails on web and pipeline paths A skill's ## Guardrails lines become an enforced ScopePolicy (tier from optional scopeTier frontmatter, default embedding-similarity), prepended on both the web streaming and A2A/AG-UI/channel pipeline paths.
- confine @AgentScope on the web streaming path too (Mode Parity #7)
- enforce @AgentScope confinement on the @Coordinator pipeline Extract reusable ScopePolicyBuilder; prepend the scope policy ahead of installed policies.
- wire OpenClaw workspace extension files into the processors AgentProcessor/CoordinatorProcessor consume RUNTIME/PERMISSIONS/SKILLS/CHANNELS/MCP; PERMISSIONS denials enforced on web, A2A and channel paths
- gate admin writes on atmosphere.admin.required-role (JWT roles)

### Fixed

- undeclare PLANNING/VIRTUAL_FILESYSTEM — native surfaces become explicit opt-ins Each surface exists on only one of the two dispatch paths (GOAP bridge: deployed-agent; FileTools: Atmosphere-native); a static declaration suppressed the portable floors everywhere, leaving the uncovered path with no plan/file surface behind an ACTIVE(native:embabel) label. Floors now own AUTO on every path; planning/filesystem=native attach the native surfaces (same reasoning as Koog). Snapshot+SKILLCARD+contract pins updated.
- keep the workspace owners probe open, gate only plan/file content
- harden planning + virtual-filesystem harness — bounds, scoping, runtime truth, auth
- don't let an OTel GenAI meter conflict crash the request path MicrometerAiMetrics dual-emits gen_ai.client.operation.duration and token.usage; when a co-resident instrumentation (quarkus-langchain4j) already owns those OTel names with different tag keys, Micrometer/Prometheus rejects the registration and the exception surfaced as an in-stream error frame (paid-nightly Gemini disconnect-recovery). Back off the dual-emit once on conflict, keeping the atmosphere.ai.* series; regression test drives a registry that rejects the registration.
- harness-aware CI lanes, tool-count truth, governed-dispatch rate budget Real-LLM lanes install the quarkus-ai-chat module closure (deployment verification needs installed artifacts, not a stale cache); orchestration spec pins the new 9-tool truth (2 user + 7 harness); the startup-team rate policy is sized for governed fan-out (one prompt = five metered tickets) and the activity spec waits for genuine connection and tolerates a spent window.
- harness review — attach-time runtime truth, delegate_task user guard, mode parity
- append grounded facts after the system prompt so provider prefix caches keep hitting time.now renders at minute granularity; schema/confidence appends splice in before the trailing fact block so volatile facts stay the suffix.
- @Coordinator classes now register on Quarkus (Mode Parity) Add @Coordinator to the build-time annotation scan and index atmosphere-coordinator when the app declares one, so CoordinatorProcessor is discovered and fleet coordinators register like on Spring Boot.
- align OpenTelemetry deps so spans reach Jaeger; pin a real Jaeger image The parent pom managed only opentelemetry-api (1.63) while sdk/exporter fell to 1.55, so the OTLP exporter died with NoClassDefFoundError InstrumentationUtil and no span exported; import opentelemetry-bom to align all artifacts and pin jaegertracing/jaeger:2.19.0 (the :2 tag does not exist). New OtlpExporterLinkageTest reproduces the export-path skew without Docker.
- broadcast the reply to the whole room, not just the asker The @AiEndpoint was missing broadcastReply=true, so streamed frames unicast to the origin while presence fanned out; a foundation-e2e spec pins fan-out to same-room members and isolation from other rooms.
- ms-governance-chat out-of-box + policy bridge merge + manual-gate postResponse
- explicit client bindings beat the demo runtime and survive auto-configuration setChatClient/setModel now mark an explicit binding (demo fallback yields keyless) and autoconfig offers instead of clobbering, so a caller-built ChatClient keeps its defaultAdvisors; new keyless foundation-e2e spec pins the advisors sample contract.
- per-transport DSL broadcast delivery; kotlin-dsl-chat WS container + logback-core Raw HTTP subscribers were never flushed, WS upgrades 501'd (no jakarta.websocket container) and the shaded jar dropped logback-core; new foundation-e2e Playwright spec pins all three from the packaged jar.
- guardrail scope no longer self-blocks an agent's own domain
- pin opentelemetry-api to 1.60.1 to match -common
- capture durable run-id in transport for reconnect resume
- close OpenTelemetry scope in postInspect to stop context leak Suspended/cancelled requests left the span thread-local-current on pooled threads (#2643).
- pin mcp-core 1.0.1 + handlebars 4.5.2, hold MCP SDK to 1.x Closes GHSA-r4gv-qr8j-p3pg (handlebars) and the mcp-core follow-up advisory; 9 alerts.
- construct SQLite run journal reflectively for native image
- reach SQLite run-journal factory reflectively for native image
- defer QuarkusSqliteRunJournalFactory to runtime init for native image
- remove duplicate jackson-annotations dependencyManagement entry The Jackson CVE pin re-declared jackson-annotations at ${jackson2.version} (2.22.0) alongside the existing ${jackson-annotations.version} (2.22) entry, a duplicate key Maven warns threatens build stability; keep the proven 2.22 pin (resolves; Jackson-3.x >=2.21 compat) and drop the redundant 2.22.0 one.
- allowlist ConditionalOnClass/SpringBootTest for cherry-picked READMEs
- embedding scope guardrail degrades to rule-based when embeddings fail A failed embed call (e.g. the provider has no matching embedding model — Gemini 404s on text-embedding-3-small) previously errored and blocked every request, bricking any @Agent with a restrictive EMBEDDING_SIMILARITY scope; it now falls back to keyword rule-based enforcement so the agent stays usable and forbidden topics are still blocked.
- add reactor-netty-http so declared WebTransport actually starts Five samples set atmosphere.web-transport.enabled=true but omitted the HTTP/3 dependency, so AtmosphereWebTransportAutoConfiguration's @ConditionalOnClass failed and the server silently no-oped while the client fell back to WebSocket; frontends rebuilt against the fixed client.
- don't surface a recovered transport-fallback as a fatal error subscribe() now suppresses the primary transport's error callback until it actually opens, so a websocket fallback that recovers a failed webtransport handshake no longer shows a stale "Opening handshake failed" banner; a genuine no-fallback failure still propagates. 608 tests green.
- guard eager runtime configure() so @Agent registers on Quarkus AgentProcessor.resolveRuntime now swallows a backend's configure() failure like AiEndpointProcessor already does, so an unready Quarkus TLS bean no longer aborts the annotation scan and drops every endpoint.
- allowlist PostgreSQLContainer for the backend-class-ref gate
- cast context_snapshot to JSONB so Postgres persists decisions
- forward stream(String,List) on DelegatingStreamingSession; scope advisors endpoint
- enforce real Cedar authorize decisions; add OPA/Cedar engine ITs Add mandatory --entities, parse plain-text ALLOW/DENY, map exit 0/2; gated real-binary integration tests + CI lane that installs the engines
- pin Jackson 3.1.4 / 2.22.0 to close CVE-2026-54512..54518 Transitive jackson-databind batch (511 alerts); annotations to 2.22.

### Changed

- skip request-heavy transport tests on the Gemini free-tier leg Gemini free tier caps at ~20 requests/day, which the multi-turn, disconnect-recovery and long-polling transport tests blow through. They are provider-independent (their own skip messages say 'requires real-ollama'), so gate them behind LLM_SKIP_REQUEST_HEAVY (set on the gemini matrix leg); they keep running on Ollama every push and OpenAI nightly. A billing-enabled Gemini key flips the flag off to run them here too.
- paid lane drives gemini-2.5-flash-lite The configured key's project is free-tier for gemini-2.5-flash (20 req/day/model per the 429 QuotaFailure payload) — one nightly plus a manual dispatch exhausts it; flash-lite's free tier sustains the schedule and stays a production Gemini model.
- rate-budget real-LLM sends so the free-tier Gemini leg stays under quota Gemini free tier caps generate_content at 5 req/min/model; the paid nightly's multi-turn and recovery specs fire ~9 prompts back-to-back and 429 (relayed as an in-stream error frame). Add a file-backed rolling-window limiter (llm-rate-budget.ts, no-op unless LLM_RPM is set) awaited before each real-LLM send; the gemini leg sets LLM_RPM=4 and a shared budget file spanning both playwright steps. Ollama and OpenAI legs run unthrottled.
- scope the session-isolation spec to local Ollama Session semantics are provider-independent; the spec's rapid back-to-back prompts trip remote free-tier per-minute limits (Gemini 429 turned the sender's stream into an instant error frame on the paid dispatch), so the remote legs skip it and keep their own streaming coverage.
- pin per-session isolation and the console echo round-trip The fanout test asserted broadcast semantics @AiEndpoint never had (never ran in CI behind the advisory mask; fails identically on the 2026-07-02 pre-harness jar) — rewritten to the ai-tools-pinned contract that another client must NOT receive a private reply; the webtransport round-trip now asserts both the user bubble and the server echo the console renders.
- dentist spec pins the harness tool count Same default-on truth as the orchestration spec: 2 user tools + 7 batteries-included harness tools = 9; grep confirms no other spec pins raw tool counts.
- resilient first send in the real-LLM ws client A cold WebSocket upgrade can drop the first data frame (the raw test handler reads one line per onRequest dispatch); a single opt-in resend makes the first turn deterministic — a genuinely broken stream still yields zero events twice and fails loudly. 3x/no-retries green locally against Ollama.
- full-reactor install in real-LLM lanes (Quarkus verification on cold cache) Scoped -pl/-am builds fail Quarkus extension dependency verification when the deployment artifact resolves in-reactor on a cold ~/.m2 (same trap ci.yml's boot gate documents); install the reactor once, then package only the runnable quarkus-run.jar.
- build the quarkus module closure when packaging quarkus-ai-chat Both real-LLM lanes packaged the sample without -am, resolving its quarkus extension deps only from a stale Maven cache; the 2026-07-04 paid nightly failed the moment the cache rotated.
- assert the omnichannel channel-bridge wiring in channels-chat Replace the shallow 'Starting service [Tomcat]' check (every Spring Boot app prints it) with the headline runtime-truth: ChannelAiBridge registers the 'omnichannel' @Agent with its AI pipeline and binds it to all five messaging channels (telegram/slack/discord/whatsapp/messenger). Deterministic + keyless; external delivery still needs bot tokens.
- drop the advisory continue-on-error hack from the real-LLM + JS lanes Take the Ollama lane off the per-push path (nightly + dispatch) so it reports honestly instead of masking failures green with a red-X-that-doesn't-count; the paid lane was already scheduled; the JS coverage upload uses codecov fail_ci_if_error:false instead of a blanket mask. Only the allowlisted release-4x website-sync dispatch keeps continue-on-error.
- add headline coverage for the passivation-agent sample Drive POST /api/agent/pause -> checkpoint -> GET inspect -> POST resume; assert the keyless DemoContinuationRuntime resumes WARM (quotes the restored history, continued=true, restoredHistorySize=2) not cold, plus 404/400 boundaries. Wire the sample into the fixture, playwright config and e2e.yml.
- assert real durable-session survival across restart The restart specs only checked a fresh connection works post-restart — green even if SQLite persistence broke. Capture the issued session token via long-polling (WS doesn't expose it), restart the JVM, assert reconnecting with it restores the SAME session + sessions.db non-empty, with an unknown-token negative control; relabel the identity test that only proved the endpoint recovers.
- fix strict-mode violation in de-flaked history assertions The keyless demo reply echoes the prompt text, so getByText(prompt) matched both the user bubble and the reply (2 elements) once the @flaky tag was removed and the tests ran strict. Target .message--user by text so the user-history assertions are unique in rag-chat and unified-console.
- de-flake the console tests with a precise assistant selector Replace the broad [class*=assistant],[class*=message] locator (which also matched user bubbles, avatars and the toolbar) with the precise .message--assistant in rag-chat and ai-tools and drop the @flaky tags; the imprecise selector also let ai-tools' multi-client test pass spuriously, so rewrite it to assert the real per-session isolation (session.stream, not a broadcast room).
- validate code-as-action wiring + real sandbox execution Assert the sample boots with code_exec registered and code-as-action enabled (keyless runtime-truth); add a Docker-gated CodeExecSandboxIntegrationTest that runs real JS/bash in a container and proves the sandbox is stateful across execs — the code-as-action substrate the full browse builds on. Full LLM-driven browse still needs a paid key + container engine (infra-gated).
- include spring-boot-ai-chat in unified-console (unmask the exclusion) ai-chat was skipped as 'WS never connects in CI'; real cause was the fixture forcing auth ON while the console sent no token, so the AuthInterceptor closed every socket. Load the console with ?token= (resolveAuthToken sends it as X-Atmosphere-Auth); consolidate ai-chat auth config to application.yml (auth off default, no properties/yml conflict); de-flake the send test with a precise assistant-bubble selector.
- release-gate fnd tier ran a dormant spec hitting a nonexistent endpoint
- harden unified-console flake + rewrite stale guarded-email spec Extract a 30s WS-connect wait so a slow-but-successful console connect isn't a flake; repoint the guarded-email spec at the real Console Validation tab + POST /api/admin/verifier/check (the sample has no /agent), asserting the @Sink taint refusal and the anonymous-write 401.
- reframe as real-time event-driven framework, tighten wording
- self-guard revived specs + drop redundant clustering/wasync no-ops Fail loud (not silent-skip) when a sample jar is missing in CI; remove the kafka/redis/wasync-client test.skip placeholders — real coverage is the Java Testcontainers lane (RedisClusteringTest, KafkaClusteringTest, wasync ChatIntegrationTest).
- revive dead-skipping integration specs + add fat-jar packaging smoke Fix wrong four-up ROOT that silently skipped otel/kotlin-dsl/parity specs; assert real behavior (WS-101, logback-active, OTLP export-health); boot shade/BOM samples from their jars in sample-startup-smoke.
- release-gate lane's dual-Playwright browser-install hole (bed4859f66)
- install Playwright browsers for both e2e and integration-tests
- bump esbuild to 0.28.1 via npm overrides Resolves Dependabot GHSA-g7r4-m6w7-qqqr (dev-server arbitrary file read) in atmosphere.js and the console frontend lockfiles; logback 1.5.33 landed separately via Dependabot PR #2701.
- bump the maven group across 62 directories with 1 update (#2701)
- guard keyless web-channel answers with a foundation-e2e spec The release-gate 'swallowed messages' was a stale-socket artifact, not a defect; this spec locks in that consecutive web turns each get a demo reply.
- gate public blog claims against the released artifact blog-claims.json manifest + validate-blog-claims.sh check non-comment code evidence at the release tag and GAV existence on Central; wired into release-4x validate + post-publish; reproduces the 4.0.59 gap (10/10 claims fail, starter GAV 404)
- packaged-artifact sample E2E gate (nightly + release-4x precondition) Boots every runnable sample from its boot/runner jar and runs its Playwright or smoke coverage; release-maven now requires the sweep green.
- prime the full reactor before packaging the smoke samples
- add sample-startup smoke job booting quarkus-ai-chat and spring-boot-ai-chat
- make integration-tests lane a real isolation lane, not duplicated coverage
- wire phantom-Javadoc validator into pre-push and index Kotlin declarations Fixes the real phantoms it caught: Javadoc misnamed GuardrailAsPolicy/PolicyAsGuardrail and GovernanceFleetInterceptor; SdkTracerProvider allowlisted as OpenTelemetry SDK.
- add phantom-Javadoc-class-ref validator (allowlist-filtered)
- bump actions/upload-artifact to v7 in sign-skillcards workflow
- cover the @Agent multimodal endpoint in the quarkus-ai-chat spec
- fail packaging if the jar lacks the Console Remove dead skip.frontend wiring; the Console is always built, including under -Pfastinstall.
- add parallel Core Integration Tests lane
- gitignore .mcp.json (per-developer local MCP config)
- rebuild Console from source via frontend-maven-plugin
- assert channel allow-list is enforced at dispatch, not just stored
- bump actions/cache from 4 to 6 (#2692)
- bump actions/checkout from 4 to 7 (#2691)
- bump org.apache.maven.plugins:maven-deploy-plugin (#2698)
- bump org.xerial:sqlite-jdbc from 3.49.1.0 to 3.53.2.0 (#2699)
- bump com.h2database:h2 from 2.3.232 to 2.4.240 (#2697)
- bump undici from 7.25.0 to 7.28.0 in /atmosphere.js (#2680)
- bump protobufjs (#2676)
- bump the sample-frontend group across 15 directories with 21 updates (#2687)
- bump opentelemetry-api.version from 1.62.0 to 1.63.0 (#2694)
- bump org.mockito.kotlin:mockito-kotlin from 5.4.0 to 6.3.0 (#2646)
- bump org.apache.maven.plugins:maven-gpg-plugin (#2644)
- hold mcp-core to 1.0.x in dependabot (block minor, not just major) Keeps it on the CVE-fixed 1.0.1 force-pin; stops the recurring 1.1.x bump PRs.
- exclude kotlin-dsl-chat — its Kotlin 2.1.10 breaks the CQL extractor CodeQL's KotlinExtractorComponentRegistrar is incompatible with the Kotlin 2.1.10 compiler the sample invokes (AbstractMethodError), failing the whole analysis build; the module is a leaf demo so excluding it from the scan build loses no real coverage.
- test the full reactor instead of a hand-picked 18-module list A -pl allowlist silently dropped every new module from per-push CI — ~29 of 47 test-bearing modules (admin, channels, rag, verifier, the *-postgres modules, etc.) were built but tested nowhere except the release job. Testing the whole reactor (like release-4x.yml) means a new module is covered the moment it exists.
- end-to-end durable crash-resume cycle across reconnect and admin
- note why browser-agent keeps @AiEndpoint over @Agent
- note why ai-classroom keeps @AiEndpoint over @Agent
- drift-log the blog make-it-true closure (4 real bugs behind green stub tests)
- prove cross-node stream relay over a real Redis backplane
- prove rooms presence tracks membership over the wire Two wAsync subscribers join/leave; presence frames delivered and Room/REST membership updates
- prove WebTransport/HTTP3 wiring in startup-team sample
- prove cost/latency model routing selects the expected model Adds offline routing-cost/routing-latency profiles and a delivery test asserting the router picks frugal-mini by cost and swift-pro by latency
- prove outbound A2A JSON-RPC call from a2a-agent sample Boots the sample's own A2A server and drives a real A2aAgentTransport round trip
- prove built-in runtime threads native schema to response_format
- remove duplicate dependency-update workflow, pin tracks in dependabot Dependabot now owns all Maven PRs; in-track ignores replace the AUTO-BUMP tier.
- correct guarded-agent sample API (PlanAndVerify, not interceptor) + drift-log
- drop false MEMORY.md/memory reads from OpenClaw adapter docs
- tighten guardrail count, PII block-vs-redact, compliance-row scope
- bump version to 4.0.59
- prepare for next development iteration 4.0.60-SNAPSHOT

## [4.0.59] - 2026-06-27

### Added

- screen long-term-memory writes for injection by default harden coverage evidence gate; relabel opt-in OWASP/compliance rows honestly

### Changed

- bump Spring Boot, LangChain4j, Embabel, ADK, Spring AI Alibaba Embabel 0.5.0 adds EmbeddingService.pricingModel; test stub reports ALL_YOU_CAN_EAT.
- record coverage-overstatement drift + self-referential-gate rule
- add obsidian skills + atmosphere-vault docs routing
- align capability comment, sample runtime defaults, and XSS skip note with reality
- add workflow_dispatch to Core and Doc Version Guard
- bump version to 4.0.58
- prepare for next development iteration 4.0.59-SNAPSHOT

## [4.0.58] - 2026-06-26

### Added

- default-on RAG injection-safety wiring across runtimes Screens retrieved RAG documents for indirect prompt injection before the LLM (fail-closed RULE_BASED/DROP); wired for @AiEndpoint, Spring Boot 3/4, and Quarkus with console runtime-truth and a poisoned-doc sample.

### Fixed

- pin rag-chat getting-started to released 4.0.57

### Changed

- stage doc sweep and fail-fast on doc-version drift before publish
- bump version to 4.0.57
- prepare next development version 5.0.35
- prepare for next development iteration 4.0.58-SNAPSHOT

## [4.0.57] - 2026-06-26

### Added

- provider-native structured output across 9 runtimes

### Fixed

- guard expo stats render against undefined token metrics
- unblock MCP Apps sandbox CSP and stop optional-tab 404 probes
- expose analyzer as @AgentSkill so approve happy path works

### Changed

- expect analyzer's headless A2A registration
- note path-scoped native structured output for Spring AI, Koog, ADK
- enforce Atmosphere doc <version> matches the released version
- retry fetch+rebase+push so a lost race can't red a green release
- apt-get update before installing libxml2-utils so the delisted stale version isn't fetched
- rebase before pushing JS dev-bump so it can't lose the race to the Maven job
- prepare next development version 5.0.34
- bump version to 4.0.56
- prepare for next development iteration 4.0.57-SNAPSHOT

## [4.0.56] - 2026-06-23

### Added

- guardrails + OBO + cost + run-registry AI bean parity with SB4
- config-driven AI routing parity with the SB4 starter
- install governance decision log out-of-box for the queryable audit trail
- warn on unauthenticated A2A endpoint + advertise declared security scheme
- make spring-boot-agui-chat a real AG-UI agent via the native bridge @Agent + @Prompt + real @AiTool through session.stream() (demo fallback when no key); AiEvent->AgUiEvent over /atmosphere/agent/{name}/agui; replaces the scripted controller
- config-driven cost/latency/model routing rules in atmosphere.ai.routing extends F3a (content-only); compose order content->model->cost->latency; off-by-default + content behavior byte-identical
- emit experimental OTel GenAI semconv span attributes via GenAiTracer gen_ai.usage.*/request+response.model/operation/provider on the live span; fixes provider Runtime-Truth bug (real runtime name, not hardcoded atmosphere); legacy ai.tokens.* byte-identical
- add --routing flag to 'atmosphere new' for routing config scaffolding injects a commented atmosphere.ai.routing.* block into AI-template application.yml; off by default, rejected for non-AI templates
- wire real LongTermMemory cross-session recall into personal-assistant InMemoryLongTermMemory + LongTermMemoryInterceptor on the UpstreamMcpAgent @AiEndpoint (no-arg interceptor + static holder pattern); makes the memory-bearing claim true
- opt-in RoutingLlmClient via atmosphere.ai.routing.enabled autoconfig wraps the resolved client with content-based routing rules; adds AiConfig.installClient seam; default-off byte-identical
- wire ApiKeyResolver into anthropic/cohere with provider env-var support renamed from CredentialResolver (clashed with AiGateway.CredentialResolver); per-provider precedence reads ANTHROPIC_API_KEY/COHERE_API_KEY, no cross-provider key leak
- add generation params (temperature/maxTokens/topP/stop) to LlmSettings wired to the wire for built-in/anthropic/spring-ai/langchain4j; honest per-runtime matrix in README; empty=byte-identical
- explicit prompt-cache-key tri-state replaces base-URL sniffing PromptCacheKeyMode AUTO/ENABLED/DISABLED on LlmSettings; AUTO preserves current per-path heuristics byte-for-byte
- store resolved apiKey on LlmSettings so apiKey() works for any client removes the OpenAiCompatibleClient-only instanceof; 4-arg constructor preserves old behavior; adds CredentialResolver precedence primitive
- provider-neutral model tier aliases (fast/frontier/reasoning) ModelTier resolves tier tokens to a concrete model by active provider; raw model strings pass through unchanged
- add provider-neutral configureNativeClient(Object) to AbstractAgentRuntime type-checked against nativeClientClassName(); provider-typed static setters remain the primary wiring path

### Fixed

- reachable agent bridge + keep response open during AG-UI streaming AgUiAgentBridge made public (cross-module reflective invoke from agui handler); handlePost joins the run thread so virtual-thread SSE writes don't hit a recycled response — fixes the agui-chat demo+UI e2e (only RUN_STARTED was reaching the wire)
- converge AUTO prompt-cache-key to one default-deny allow-list built-in and framework runtimes share CacheHint.endpointAcceptsPromptCacheKey; framework no longer emits on unknown hosts under AUTO (force via PromptCacheKeyMode.ENABLED)
- honor per-request ToolLoopPolicy in anthropic/cohere/langchain4j tool loops they hardcoded a 5-round cap and ignored maxIterations/onMaxIterations; now route through the shared ToolLoopGuard like the built-in (default behavior unchanged)
- make default model configurable, default claude-sonnet-4-6 adds anthropic.model system property; per-request and AiConfig models still win over the fallback
- version-bump touches only Atmosphere deps; regen SKILLCARDs
- repair rag-chat/mcp-server/browser-agent + SB3 Tomcat + stream errors
- restore real third-party dep versions clobbered by 4.0.x bump

### Changed

- pin deny-policy refusal on A2A and AG-UI bridges
- fail-closed Maven Central pre-check guards against version burn
- e2e proving config-driven routing is consumed on the wire
- attribute GenAI "experimental" to the OpenTelemetry spec, not the emitter GenAiTracer/MetricsCapturingSession Javadoc + README make clear the implementation is production code; only the upstream OTel GenAI convention is experimental
- document atmosphere new --routing flag and correct agui-chat sample row agui-chat is now a real @Agent (LLM + @AiTool over the AG-UI native bridge), not scripted
- consolidate per-model-call observability into ModelCallScope replaces duplicated fireModelStart/End/Error + timing across 9 adapters and 2 Kotlin runtimes; event count/ordering unchanged (ADK start-time aligned to dispatch)
- unify tool-call accumulator across built-in, anthropic, cohere shared ToolCallAccumulator gains argumentsAsMap(); deletes the two private copies; built-in parse path unchanged
- extract AbstractSseLlmClient shared by Anthropic and Cohere clients collapses ~268 lines of duplicated HTTP/SSE plumbing (header filter, snippet read, data: loop, tool-schema) into one base; wire behavior byte-identical (black-box suites unchanged)
- add TokenUsage.fromCounts and migrate adapter usage translation collapses 11 hand-rolled null-guard/total-fallback sites; 2 sites now compute total=input+output instead of 0 when the provider omits total (regression-tested)
- hoist models() default into AbstractAgentRuntime removes 9 byte-identical adapter overrides; koog (distinct logic) and the interface default unchanged
- fix cancel-test race by awaiting worker interrupt observation worker records the interrupt asynchronously; wait on a latch instead of reading the flag right after whenDone()
- correct AI doc/sample drift vs verified runtime capabilities embeddings 5->7 runtimes, TOOL_CALL_DELTA Built-in+Cohere, ai.md classpath table, agui relabel, samples.json reattach
- bound playwright install with timeout to prevent multi-hour hangs
- both-layer regressions for the sweep failures (JUnit + Playwright)
- sync version stamp to 4.0.56-SNAPSHOT
- deep-link concept tables to docs site; fix verifier count
- bump version to 4.0.55
- prepare next development version 5.0.33
- prepare for next development iteration 4.0.56-SNAPSHOT

## [4.0.55] - 2026-06-17

### Added

- **Static verifier over MCP.** The plan-and-verify ("Guardians") stack is
  reachable as read-only MCP tools when `atmosphere-verifier` is on the
  classpath: `atmosphere_verifier_summary`, `atmosphere_verifier_examples`, and
  `atmosphere_verifier_check`. The check tool plans a goal and runs every
  verifier over the resulting plan **without executing it** (status
  `verified`/`refused` with the per-verifier violations); the mutating
  verify-then-execute path stays behind the admin write gate.

### Fixed

- **wasync: a user `close()` is no longer resurrected by a late OPEN event** —
  a close requested before the transport finished opening is now honored when
  the OPEN arrives, instead of reviving the connection.
- **Documentation accuracy sweep** — corrected the Z3 binding version (4.14.0)
  and `SmtChecker` priority (200); aligned ADK / Semantic Kernel / Alibaba
  versions, crewai test counts, and the ms-governance policy name; fixed the
  embedded-jetty client type, the admin-bundle default authorizer, and
  runtime-truth claims in the embabel / agentscope / coding-agent docs;
  corrected third-party dependency versions and citations.

### Changed

- **CI doc-drift gates** — fact/enumeration checks, link-rot detection, and
  sibling-site (atmosphere.github.io) verification, plus an `atmosphere-skills`
  link-checker allowlist.

## [4.0.54] - 2026-06-13

### Added

- **Rich human-in-the-loop approval payloads.** A reviewer can now resolve a tool
  approval with more than approve/deny: **approve-with-edited-arguments** (the
  tool runs with the reviewer's arguments) or **respond** (the reviewer answers on
  the tool's behalf — structured JSON or free-form text — and the tool does not
  run). Wire protocol: `/__approval/<id>/approve {"arguments":{…}}` and
  `/__approval/<id>/respond {…}`. Fail-safe: a malformed edited-args payload denies.
  Session-scoped in-memory (not crash-durable). The legacy boolean resolution path
  is unchanged.
- **Eval flywheel.** `JournalDatasetPromoter` turns a recorded `CoordinationJournal`
  interaction into an `EvalCase` dataset row (trace→dataset), and `SampledLiveScorer`
  grades a configurable fraction of live turns into `EvalRun` verdicts (online
  scoring). Both are wired into `EvalController` with admin REST routes
  (`/api/admin/evals/dataset`, `/dataset/promote`, `/score`).
- **OAuth on-behalf-of credential vault.** `OAuthOnBehalfOfCredentialStore` is a
  concrete `CredentialStore` that performs an RFC 8693 token exchange — swapping a
  user's stored subject token for a short-lived access token scoped to a downstream
  tool, so an agent calls external APIs *as the user*. Fail-closed (no token →
  no fallback credential), token-cached until expiry. Opt in with
  `atmosphere.ai.identity.oauth-obo.enabled=true`.
- **Realtime voice bridge.** `VoiceBridge` + the `RealtimeVoiceProvider` SPI bridge
  client audio frames over the existing WebSocket broadcaster to a speech-to-speech
  provider, fanning synthesized audio (`Content.Audio`) and transcripts back to the
  client. A dependency-free `LoopbackVoiceProvider` ships as the runnable reference
  (echoes audio); OpenAI Realtime / Gemini Live providers implement the same SPI.
- **Content-safety moderation guardrail.** `ModerationGuardrail` blocks turns
  whose request and/or response is flagged for hate / harassment / self-harm /
  sexual / violence / illicit content, on the existing fail-closed guardrail
  pipeline. Pluggable detector: zero-dep `RuleBasedModerationDetector` (default)
  or cross-runtime `LlmModerationDetector`. Fail-closed by default (a detector
  outage blocks the turn; `.failOpen()` is the explicit opt-out). Opt in with
  `atmosphere.ai.guardrails.moderation.enabled=true`
  (`...detector=llm` for the model tier).
- **Self-healing structured output.** `@AiEndpoint(structuredOutputRetries = N)`
  (or the `ai.structured.retry` request-metadata key on the `AiPipeline` path)
  re-prompts the model with the schema-validation error as feedback when a typed
  response fails to parse, up to `N` extra attempts, then fails closed. Works
  identically on the websocket and channel-bridge paths.
- **OpenAPI → governed tools.** `OpenApiToolImporter` turns an OpenAPI 3.x spec
  (JSON or YAML, with local `$ref` resolution) into `ToolDefinition`s whose
  executor performs the HTTP call. The imported operations ride the same
  policy-admission and plan-and-verify path as hand-written `@AiTool` methods;
  `approvalForWrites` routes mutating verbs through the HITL gate.
- **MCP client depth.** `McpClientOptions` adds per-server tool filtering and
  display-only renaming (the executor still calls the server's original tool
  name), plus elicitation/sampling callback handlers advertised during
  `initialize`. `McpServerRegistry` aggregates several servers into one
  collision-free tool list (first-wins) and owns their lifecycle.

## [4.0.52] - 2026-06-08

### Added

- **MCP authorization now validates bearer tokens end-to-end.** A request is authenticated
  when either a servlet resource-server filter set the request principal (e.g. Spring
  Security `oauth2ResourceServer`) **or** a configured `TokenValidator` accepts the
  `Authorization: Bearer` token (loaded from `org.atmosphere.auth.tokenValidator`, validated
  by `atmosphere-mcp` itself — no framework-specific wiring). The RFC 9728 metadata is now
  served on the agent registration path too. Proven end-to-end on the embedded server,
  Spring Boot, and Quarkus (JVM). The `spring-boot-mcp-server` sample gains an opt-in `auth`
  profile (default off) demonstrating it.
- **MCP runs on Quarkus.** `@Agent`-based MCP endpoints now register under the Quarkus
  extension (the build scan recognizes `@Agent` and indexes the optional
  `atmosphere-agent` / `atmosphere-mcp` jars when an `@Agent` class is present). JVM mode;
  native image is not yet supported for `@Agent`-based MCP.

### Tested

- Added a stateless `2026-07-28` round-robin end-to-end test (two `tools/call` with no
  session header both succeed, plus `server/discover` and `Mcp-Method` mismatch) in
  `modules/integration-tests`, proving the no-session-affinity claim over live HTTP.

## [4.0.51] - 2026-06-06

### Added

- **MCP `2026-07-28` release candidate** — the largest MCP revision since launch,
  implemented as a **stateless dialect that coexists** with the session-based protocol
  (`2024-11-05` through `2025-11-25`). The dialect is selected per request (the client
  carries the protocol version in `params._meta` or calls `server/discover`), so existing
  clients are unaffected. Stateless core has no `Mcp-Session-Id` and no `initialize`
  handshake, so the server runs behind a plain round-robin load balancer with no session
  affinity.
- **MCP operability** — `Mcp-Method` / `Mcp-Name` routing headers (validated against the
  body), `ttlMs` + `cacheScope` cache metadata on `tools/list` / `resources/list` /
  `resources/read`, and W3C Trace Context (`traceparent` / `tracestate` / `baggage`) read
  from `_meta` and bridged into the OpenTelemetry span.
- **MCP Tasks extension** (`io.modelcontextprotocol/tasks`) and multi-round-trip input —
  `@McpTool(longRunning = true)` returns a task handle polled via `tasks/get`, and the
  stateless dialect can return `InputRequiredResult` with a base64 `requestState` to
  request more input mid-call and resume on any instance.
- **JSON Schema 2020-12** dialect (`$schema`) on generated tool input schemas, and a
  standardized resource-not-found error (`-32602`) on the stateless dialect.
- **MCP Apps (SEP-1865)** — `@McpTool(uiResource = "ui://…")` plus a
  `text/html;profile=mcp-app` resource makes a tool an MCP App. The Atmosphere console is a
  working host: it renders the app in a sandboxed iframe, runs a **bidirectional App
  Bridge** (apps call server tools through the host under the policy gateway; the host
  lists and calls the app's own `appCapabilities.tools`), and uses a **separate-origin
  sandbox proxy** for isolation (`atmosphere.mcp-sandbox-origin`, with a `localhost`↔
  `127.0.0.1` dev fallback, otherwise an opaque-origin direct sandbox).
- **MCP authorization (protocol glue)** — the server acts as an OAuth 2.0 Resource Server:
  RFC 9728 protected-resource metadata at `/.well-known/oauth-protected-resource` and a
  `401` + `WWW-Authenticate` challenge for unauthenticated requests; opt in via the
  `org.atmosphere.mcp.auth.*` init parameters. This release shipped the protocol glue only;
  bearer-token validation was wired end-to-end in 4.0.52 (see Unreleased).

## [4.0.50] - 2026-06-05

### Removed

- Pruned dead/unwired internal classes found during a release-readiness audit —
  none was documented, advertised, or reachable from a user code path:
  `McpWebSocketHandler` (superseded by `McpHandler`'s direct WebSocket-frame
  handling), `AgUiSession` (superseded by `ResourceAgUiStreamingSession`),
  `AiCoalescingBroadcasterCache` (a delegate-only `BroadcasterCache` that the
  no-arg reflective cache-wiring path cannot instantiate), `AdkArtifactBridge`,
  `AdkCompactionBridge`, `AtmosphereRequestBridge`, `AtmosphereResponseBridge`,
  the channels `AuditLoggingFilter` (never registered as a bean, so it never
  reached the filter chain), the unwired `GrpcProtocolBridge`, and the A2A
  `ListTaskPushNotificationConfigsResponse` DTO (the
  `ListTaskPushNotificationConfigs` method returns `ERROR_PUSH_NOT_SUPPORTED`,
  so the response type was never constructed).

### Fixed

- `ToolBridgeUtils.findUnescapedQuote` no longer advances the scan index past
  the end of the string when malformed tool-call JSON ends in a lone backslash
  — the escaped-character skip is now bounds-checked (boundary safety,
  Correctness Invariant #4). Regression test added.

### Added

- **Interactions API** (`org.atmosphere.interactions`, artifact `atmosphere-interactions`)
  — a stateful agent-turn resource layered above the `AgentRuntime` SPI, so it
  works for every adapter with no per-runtime code. An `Interaction` carries a
  stable id, a durable `steps[]` event log, and chains turns via
  `previousInteractionId` (the server holds history; the client does not resend
  it). Turns run **synchronously** or in the **background** (`background=true`
  returns a `RUNNING` record immediately and is retrievable after a disconnect),
  and `store=false` streams without persisting. The starter exposes the resource
  over `POST /api/interactions`, `POST /api/interactions/{id}/continue`,
  `GET /api/interactions/{id}`, `GET /api/interactions`,
  `POST /api/interactions/{id}/cancel`, and `DELETE /api/interactions/{id}` —
  every mutating route is default-deny behind `atmosphere.interactions.http-write-enabled`
  plus an authenticated principal (Correctness Invariant #6). Two `InteractionStore`
  backends ship: `InMemoryInteractionStore` (default) and `SqliteInteractionStore`;
  the SPI is pluggable for others. `atmosphere.js` gains a typed `InteractionsClient`
  (`atmosphere.js/interactions`) covering the REST surface plus `pollUntilTerminal`
  / `watch` helpers.
- **Interactions live streaming** — a background interaction now streams its
  durable `steps[]` to a subscribed browser as they are produced, over the
  Atmosphere transport (`/atmosphere/interactions-stream?id=<id>`, WebSocket/SSE).
  On connect the handler replays the steps captured so far (late-joiner catch-up,
  deduped client-side by sequence), then pushes each new step live and a terminal
  frame on completion; ownership is enforced per-interaction, same scope as the
  REST read. `InteractionsClient.subscribe(id, handlers)` bridges it on the client
  and the Atmosphere Console's **Interactions** tab renders the live step timeline.
  An `AtmosphereInterceptor` resolves the principal for the stream socket so
  ownership holds across all transports (a servlet filter's request attribute does
  not survive the WebSocket upgrade). Demonstrated in `spring-boot-coding-agent`
  and `spring-boot-multi-agent-startup-team`.

- `ToolKind` + `@AiTool(kind = …)` — tools declare a behavioural category
  (`EDIT`, `READ`, `EXECUTE`, `NETWORK`, `DELETE`, `OTHER`; default `OTHER`).
  This makes `PermissionMode.ACCEPT_EDITS` a real behaviour instead of a
  `DEFAULT` alias: it now auto-approves a tool's own `@RequiresApproval`
  prompt when `kind == EDIT`, while every other tool still routes through the
  per-tool approval gate. The classification is compile-time author metadata
  (not runtime caller-asserted intent), the default `OTHER` keeps the approval
  posture exactly as restrictive as before, and the relaxation never overrides
  an operator-configured `ToolPermissionPolicy` `DENY`/`CONFIRM` or a `DenyAll`
  policy. `ToolExecutionHelperAcceptEditsTest` pins all four cases.

- **Code-as-action sandbox** (`org.atmosphere.ai.code`) — a `code_exec` tool that
  lets a model accomplish tasks by writing a block of code (bash / JavaScript /
  Python) instead of negotiating many fine-grained tool calls. Each session gets
  an isolated, ephemeral container (`CodeSandbox` SPI, `ContainerCodeSandbox` over
  Docker/Podman) with hardening applied — `--network none` by default, non-root,
  `--cap-drop ALL`, `--security-opt no-new-privileges`, read-only rootfs + a bounded
  writable workspace, and memory/cpu/pid caps — provisioned lazily on first use and
  torn down on every terminal path via the new `StreamingSession.onTerminate(AutoCloseable)`
  primitive. **Default-deny**: code execution is off unless
  `org.atmosphere.ai.code.enabled=true` and a container engine is confirmed present
  at runtime (Correctness Invariant #5); the tool is registered into `@AiEndpoint`
  dispatch only then, with the tool-loop ceiling lifted to 25 write→run→observe
  rounds. Each round streams an `AiEvent.AgentStep` plus any screenshots the code
  produced, rendered inline in the Console as markdown data-URI images. New
  `samples/spring-boot-browser-agent` demonstrates it (Cohere-backed, requires
  Docker): the agent drives a headless browser with Playwright and you watch the
  screenshots arrive live.

### Changed

- `ai-policy-rego` and `ai-policy-cedar` now ship a
  `META-INF/services/org.atmosphere.ai.governance.PolicyParser` registration,
  so Rego and Cedar policy artifacts are auto-discovered by `ServiceLoader`
  the same way YAML always has been — no programmatic parser wiring required.
  Safe because both parsers have lazy no-arg constructors (the `opa` / `cedar`
  binary is only touched at evaluation, and parse failure is already
  fail-closed). The Kafka/Postgres **audit sinks** are deliberately left on
  programmatic `GovernanceDecisionLog.addSink()` wiring: they need a live
  broker / JDBC connection, so auto-activating them on classpath presence
  would advertise capability that cannot run (Runtime-Truth, Correctness
  Invariant #5). `RegoPolicyParserTest` / `CedarPolicyParserTest` pin the
  discovery.
- **Four new pre-push drift-prevention gates**, each closing a class the
  `.harness/drift-log.md` had recorded but left un-automated, wired into
  `scripts/pre-push-validate.sh` Tier-1:
  `validate-runtime-overlay-coverage.sh` (every snapshot runtime must have a
  CLI overlay and a `bom/pom.xml` artifact — drift #59);
  `validate-dangling-doc-comments.sh` (parse-only `javac -Xlint:dangling-doc-comments`
  under a JDK ≥ 23 to catch detached Javadoc locally, not only on the Native
  Image lane — drift #80);
  `validate-doc-version-alignment.sh` (third-party dependency versions in
  Markdown must match the pinned `pom.xml`/`package.json` — drift #12/#18/#75);
  and `validate-doc-symbols.sh` (annotation references in Markdown must resolve
  to an in-tree declaration or a curated external allowlist — drift #72). Two of the
  gates caught a pre-existing drift on first run: `atmosphere-semantic-kernel`
  was missing from `bom/pom.xml` (now added), and `modules/langchain4j/README.md`
  named LangChain4j 1.12.2 while the pom pins 1.15.0 (now corrected). CLI overlay
  coverage was also extended to the three native runtimes (`anthropic`, `cohere`,
  `crewai`) in `cli-e2e.yml` path filters and the `test-cli.sh` scaffold+compile
  matrix.

## [4.0.49] - 2026-05-28

### Added

- `atmosphere-crewai` — `AgentRuntime` for the
  [CrewAI](https://www.crewai.com/) multi-agent framework via an
  out-of-process Python sidecar. First non-Java runtime adapter in the
  project; the boundary is `HTTP + SSE` for the request stream plus a
  loopback `ToolCallbackServer` for Java→Python tool RPC. Pins 9
  capabilities (`TEXT_STREAMING`, `TOKEN_USAGE`, `AGENT_ORCHESTRATION`,
  `CANCELLATION`, `TOOL_CALLING`, `SYSTEM_PROMPT`,
  `STRUCTURED_OUTPUT`, `TOOL_APPROVAL`, `PER_REQUEST_RETRY`) via
  `CrewAiRuntimeContractTest` + the capability snapshot (which now
  enumerates 12 runtimes). Like every other runtime, `isAvailable()`
  is config-gated — requires `ATMOSPHERE_CREWAI_SIDECAR_URL` pointing
  at a running sidecar that responds OK to `GET /health`.
- `modules/crewai/sidecar/` — companion Python package
  `atmosphere-crewai-bridge` (FastAPI + uvicorn + crewai 1.14)
  speaking the documented wire protocol. Materialises Java
  `ToolDefinition`s as `crewai.tools.BaseTool` subclasses via
  `pydantic.create_model`, injects them into agents, and threads
  `context.systemPrompt()` into each agent's `backstory` inside a
  delimited block. Ships with a working `examples/ollama_crew.py`
  factory that targets `qwen2.5:0.5b` (no API key required).
- CLI runtime overlay (`cli/runtime-overlays.json`) for `crewai`, so
  `atmosphere new my-app --template ai-chat --runtime crewai`
  scaffolds with the dependency wired and the sidecar setup
  documented inline.
- End-to-end validation captured at
  `.harness/crewai-e2e-success.png`: chrome-devtools drove
  `/atmosphere/console/` against a real Ollama-backed crew; the
  browser rendered 25 tokens at 46.8 tok/s through the full chain
  `WebSocket → @AiEndpoint(runtime=crewai) → HttpSseSidecarClient →
  atmosphere-crewai-bridge → crewai 1.14 → litellm → Ollama`. Console
  zero errors, sidecar log confirms `POST /v1/chat/completions
  HTTP/1.1 200 OK` against the local Ollama instance.
- `modules/coordinator/journal` — **event-sourced execution log** for
  the coordinator. Layers four additive pieces onto the existing
  `CoordinationJournal` SPI without breaking any of the 119 existing
  `new CoordinationEvent.*` call sites across coordinator / admin /
  checkpoint / integration-tests:
  1. `EventEnvelope(eventId, parentEventId, event)` + default-method
     `recordEnveloped` / `retrieveEnveloped` on `CoordinationJournal`.
     `JournalingAgentFleet` threads parent IDs through every dispatch
     path (`parallel` / `pipeline` / `route` / `proxy.call` /
     `callAsync` / `stream`): `CoordinationStarted` → `AgentDispatched`
     → `AgentCompleted`/`AgentFailed` → `AgentEvaluated`. Legacy
     `record(event)` callers continue working — events are wrapped as
     root envelopes with no parent.
  2. `CoordinationProjection.from(journal, coordinationId)` — pure
     read-only causal DAG built from `retrieveEnveloped`. Exposes
     `roots()`, `children(eventId)`, `walk(visitor)`, `agents()`,
     `failedDispatches()`, `evaluations()`. No execution, no LLM, no
     side effects.
  3. `FileCoordinationJournal(Path)` — append-only NDJSON file backend,
     one JSON object per line. Replays on `start()` into an in-memory
     index for queries; tolerates a truncated final line from a JVM
     kill mid-append (logs and skips). Single-writer locked appends;
     polymorphic ser/deser of the sealed `CoordinationEvent` hierarchy
     via a Jackson 3 mix-in so the event records stay annotation-free.
  4. `CoordinationFork` + new `ForkCreated` event variant — what-if
     branching primitive. `fork.from(coordId, eventId).reason(...).with(altCall).execute(fleet)`
     creates a new coordination id (or accepts an explicit one),
     records a `ForkCreated` envelope linking back to the parent event,
     and runs the alternate dispatch via
     `JournalingAgentFleet.withCoordinationId(...)`. The parent
     coordination is immutable; the fork is a peer with its own future.
     Pre-flight check rejects unknown `parentEventId` with a fast
     `IllegalArgumentException`.

  Backed by 78 tests in `modules/coordinator/src/test/java/.../journal/`
  including a three-process integration test that runs a parallel
  coordination, restart-replays from disk, projects the DAG, forks an
  alternate, restart-replays again, and verifies both the original and
  the forked branch survive across two simulated JVM kills.
  `modules/coordinator/README.md` documents the new surface.
- Cohere `TOOL_CALL_DELTA` streaming capability (`3327425d50`).
  `CohereChatClient.handleToolCallDelta` surfaces incremental tool-call
  argument fragments as they arrive, and `CohereAgentRuntime`
  (line 269) now declares `TOOL_CALL_DELTA`. The same honesty pass
  *removed* `PROMPT_CACHING` from Cohere — the v2 API exposes no
  prompt-cache control, so advertising it was Runtime-Truth drift; the
  capability snapshot was re-pinned accordingly.
- Quarkus extension integration parity: five optional surfaces, each
  gated on classpath presence and covered by a dedicated build-step
  test (`3327425d50`). `AtmosphereProcessor` registers Cache, Health
  (`HealthBuildItem`), Micrometer metrics
  (`AtmosphereMetricsProducer`), OpenTelemetry tracing
  (`AtmosphereTracingProducer`), and governance metrics
  (`AtmosphereGovernanceMetricsProducer`) producers — see
  `AtmosphereProcessor.java:432-510` and the
  `Atmosphere{Cache,Health,Metrics,Tracing,GovernanceMetrics}BuildStepTest`
  suite.
- `modules/quarkus-grpc` — Quarkus gRPC bridge extension (`runtime` +
  `deployment` submodules) (`3327425d50`).
- `scripts/validate-no-beta-on-main.sh` — push-time gate enforcing the
  release-frequency rule: pre-GA escape-hatch framing (beta annotations,
  hourglass deferral markers, phased planning labels, or roadmap-deferral
  prose) introduced relative to `origin/main` fails the build, so `main`
  stays release-ready (`3327425d50`).

### Changed

- Bumped JetBrains **Koog `0.8.0 → 1.0.0`** (`4685a844bb`, root pom
  `koog.version`) — Koog's first GA. The adapter configures via
  Koog 1.0's stable `OpenAILLMClient` / `MultiLLMPromptExecutor`
  (`AtmosphereKoogAutoConfiguration.kt`); the full Koog capability set
  (`VISION`, `AUDIO`, `MULTI_MODAL`, `PROMPT_CACHING`, `TOOL_CALLING`,
  `TOOL_APPROVAL`, …) is unchanged and re-pinned by
  `KoogRuntimeContractTest` + the capability snapshot.
- Bumped `langchain4j.version` `1.14.0 → 1.15.0` (`abd774f68d`),
  `logback-version` `1.5.25 → 1.5.32` (`58f2e6d373`), and
  `commons-lang3` `3.18.0 → 3.20.0` (`8dea5788ac`).

### Fixed

- `HttpSseSidecarClient` now pins `HttpClient.Version.HTTP_1_1`. The
  JDK's `java.net.http.HttpClient` defaults to HTTP/2 for plain HTTP
  and attempts an `Upgrade: h2c` negotiation; uvicorn (the FastAPI
  host for the CrewAI sidecar) does not implement the h2c upgrade and
  the resulting request lands with an empty body, which FastAPI
  rejects as `422 Field required, loc=["body"], input=null`. The
  bridge-test `FakeSidecar` (a
  `com.sun.net.httpserver.HttpServer`) tolerated the upgrade preamble
  and parsed the body anyway, so the bug only surfaced under real
  uvicorn — exactly the gap `feedback_chrome_devtools_only.md` warns
  about. Added a regression test
  (`CrewAiAgentRuntimeBridgeTest.httpClient_pinnedToHttp11`) that
  reflects into the client and asserts the version, so a future "just
  use the default `HttpClient`" refactor breaks the build before it
  breaks production. Drift recorded as `.harness/drift-log.md` #64.
- Koog runtime reaches **Gemini via Google's OpenAI-compatible base
  URL** (`87aa2cc824`). Koog 1.0's native Google client ships only on a
  JVM-incompatible path, so `AtmosphereKoogAutoConfiguration` points the
  stable `OpenAILLMClient` at any OpenAI-compatible endpoint when
  `atmosphere.koog.base-url` / `LLM_BASE_URL` is set (e.g.
  `https://generativelanguage.googleapis.com/v1beta/openai` for
  `gemini-2.5-flash`). Regression-gated by
  `AtmosphereKoogAutoConfigurationTest`. Drift recorded as
  `.harness/drift-log.md` #77 — the `0.8.0 → 1.0.0` bump had been
  reported done on CI alone, which hid the dropped-Gemini regression.
- Spring Boot **JDK 26 long-term-memory disconnect hang** resolved via
  an idle-reaper fallback (`b2e9e09e71`).
  `LongTermMemoryHttpE2eTest`'s disconnect path intermittently hung on
  the JDK 26 lane because the WebSocket-close → `onDisconnect` lifecycle
  could be dropped under fork contention; an
  `IdleResourceInterceptor`-based reaper (platform-thread scheduler,
  `maxInactiveActivity=5000`) now fires the disconnect lifecycle
  independently, so suspended resources are reaped and facts persisted
  even when the close frame is lost. Drift recorded as
  `.harness/drift-log.md` #78–#79 — an earlier 60s → 120s await bump was
  ineffective (a timeout cannot fix a hang).

### Security

- Bumped `tomcat-embed-core` `11.0.21 → 11.0.22` (root pom
  `tomcat-version` property) to close 7 Dependabot advisories — 3
  critical (security-constraint bypass `GHSA-5m62-pw8w-7w9f`,
  digest-auth bypass `GHSA-h6fc-48rj-7qqh`, HTTP/2 header validation
  `GHSA-r29c-68gh-xp6x`), 3 high (LockOutRealm case-sensitivity
  `GHSA-5mp6-jrq3-r938`, WebSocket auth-header exposure
  `GHSA-fv25-8xcx-gqjc`, WebDAV `LOCK`/`PROPFIND` unbounded read
  `GHSA-gx5v-xp9w-j4cg`), and 1 low (AJP secret non-constant-time
  compare `GHSA-9m89-8frq-c98c`). The pin stays scoped to
  `tomcat-embed-core`; `tomcat-embed-el` and `tomcat-embed-websocket`
  continue to follow each Spring Boot BOM (3.5.x keeps the 10.1.x
  line, 4.0.x stays on 11.0.x).
- Bumped `protobufjs` `7.5.6 → 7.5.8` in
  `modules/integration-tests/package.json` + lockfile to close
  `GHSA-jggg-4jg4-v7c6`.
- Dismissed 3 remaining open Dependabot alerts that have no in-tree
  fix path. Two `org.json:json` alerts (`GHSA-3vqj-43w4-2q58`,
  `GHSA-4jq9-2xhw-jpx7`) cited `modules/runtime/pom.xml` — a manifest
  that no longer exists; `org.json:json` was removed reactor-wide in
  commit `4f40968d4d` (4.0.42-SNAPSHOT, "drop org.json:json — Jackson
  3 only"). Dismissed as `not_used`. One `opentelemetry-api` alert
  (`GHSA-rcgg-9c38-7xpx` / `CVE-2026-45292`, medium DoS via unbounded
  W3C Baggage Propagation) is blocked upstream: Quarkus 3.35.x and
  3.36.0 both ship OTel `1.60.1`, and the `samples/quarkus-ai-chat`
  pin must follow the BOM to keep `quarkus-micrometer-registry-prometheus`
  working. Dismissed as `tolerable_risk` (sample, baggage propagation
  not enabled, Vert.x enforces the 8 KiB header limit recommended in
  the advisory). Re-evaluate when Quarkus's BOM picks up OTel 1.62+.

## [4.0.48] - 2026-05-25

### Added

- CLI runtime overlays for `anthropic` and `cohere`
  (`cli/runtime-overlays.json`). Both runtimes had been shipped in
  `modules/` and documented in the top-level README — `atmosphere-anthropic`
  since 2026-05-19 (`1195845304`), `atmosphere-cohere` since 2026-05-23
  (`1dfebcb5ff`) — but neither had a CLI scaffolding overlay. The
  command `atmosphere new my-app --template ai-chat --runtime cohere`
  (or `--runtime anthropic`) now works. Same change adds both artifacts
  to `bom/pom.xml` and the parent `pom.xml`'s
  `<dependencyManagement>` so a Maven build resolves their version
  without an explicit `<version>` in the consuming pom. Verified
  end-to-end via chrome-devtools against the Atmosphere Console: real
  Cohere `command-a-plus-05-2026` LLM response over WebSocket with
  18.3 tok/s streaming through the `CohereChatClient` → real
  `https://api.cohere.com/v2/chat` HTTP call.
- Durable hibernating `Workflow<S>` primitive in
  `atmosphere-checkpoint` (`a0ac15f1e3`). Orders `WorkflowStep<S>`
  instances over an application-owned state type and composes the
  existing `CheckpointStore` SPI for persistence. Sealed
  `StepOutcome<S>` (`Advance` / `Hibernate` / `Done` / `Fail`) and
  `WorkflowResult<S>` (`Completed` / `Hibernated` / `Failed`) drive
  the runner; per-step `maxRetries()` + `retryDelay()` cover
  transient failures. Hibernation is return-not-park: a step that
  returns `StepOutcome.hibernate(state)` writes a snapshot and the
  call returns to the caller with no platform thread held; a later
  `run()` against the same coordination resumes at the next
  un-completed step, including across JVM restarts when the store
  is persistent. `WorkflowSqliteResumeTest` pins the cold-restart
  contract — closes `SqliteCheckpointStore`, opens a fresh handle
  on the same file, builds a fresh `Workflow` instance, and asserts
  only the un-completed step executes. Ten unit tests cover linear
  execution, hibernate-and-resume, retry-success, retry-exhaustion,
  explicit fail, duplicate-step-name rejection, snapshot-precedence
  over `initialState`, and `deleteAllSnapshots`.
- `SqliteLongTermMemory` and `RedisLongTermMemory` (`835a88d252`,
  rebased to `fbbfa457a2`) — persistent backends for the
  `LongTermMemory` SPI in `atmosphere-durable-sessions-sqlite` and
  `atmosphere-durable-sessions-redis`. Both can share a connection
  with their sibling `SessionStore` / `ConversationPersistence`
  implementations. `LongTermMemoryBackendIntegrationTest`
  parameterizes the full `LongTermMemoryInterceptor` round-trip
  over all three backends; `LongTermMemoryMultiInstanceTest`
  (`d4609cf0fc`) proves the pod-A-writes / pod-B-reads scenario the
  persistent backends exist for, using two independent
  `LongTermMemory` handles against the same shared store.
- `scripts/validate-backend-class-refs.sh` (`835a88d252`) —
  structural gate scanning `*.java` Javadoc and `*.md` documentation
  for `(Sqlite|Redis|Postgres|Mongo|Cassandra|Hazelcast|JGroups|Kafka|Nats)<Word>`
  tokens that don't resolve to a declared class in
  `modules/**/src/{main,test}/java/`. `.harness/external-class-allowlist.txt`
  whitelists genuine third-party types (Lettuce, Kafka, Testcontainers,
  brand names). Wired into `pre-push-validate.sh` Tier-1. Catches
  future SPI-backend overclaim drift before it merges (the class of
  bug that drift #53 logged on the original `LongTermMemory`
  Javadoc).
- Per-runtime `SKILLCARD.yaml` manifests with OpenSSF Model Signing
  (`32a8e8b935` + this commit) — capability + provenance documents that
  are signable via the same Sigstore-keyless toolchain NVIDIA's
  verified-agent-skills programme uses. Cards ship unsigned
  (`signing.status: unsigned`); signatures are produced at release-tag
  time by `.github/workflows/sign-skillcards.yml`.
  `scripts/regen-skillcards.sh` emits one card per
  snapshot-pinned runtime at `modules/<X>/SKILLCARD.yaml`, derived
  from `.harness/capabilities.snapshot.json` and each module's
  `pom.xml`. The repo-root `SKILLCARDS.md` catalog (regenerated by
  the same script) lists every shipped runtime, its capability
  count, and its signature state — that file is the source of
  truth for "which runtimes have a card", not this paragraph (a
  named list and count would stale every time a new adapter lands).
  Each card declares `spec: atmosphere/skillcard/v1` with artifact
  coordinates, the registered `AgentRuntime` SPI implementation FQN,
  the alphabetical+count-pinned `AiCapability` set, contract-test
  path, license (Apache-2.0), and a `signing` block referencing
  `signature_file: SKILLCARD.yaml.sig` with `envelope:
  openssf-model-signing/v1` and `bundle_format: sigstore`.
  `.github/workflows/sign-skillcards.yml` signs every card on tag
  push via `model_signing sign sigstore` — short-lived Fulcio cert
  + Rekor transparency-log entry, OIDC identity bound to the
  workflow path — and uploads the `.sig` files as a GitHub release
  asset and as a workflow artifact for downstream bundling. Cards
  AND signatures are bundled into each runtime jar at
  `META-INF/atmosphere/` via a root-pom `<resource>` declaration so
  consumers can verify integrity without unpacking the source tree;
  modules without a SKILLCARD (cpr, mcp, channels…) see the include
  filter match nothing and are unaffected. `SkillCardSnapshotTest`
  in `atmosphere-ai-test` enforces three contracts: (a) capability
  set + count drift against the snapshot, (b) every snapshot runtime
  has a card on disk, (c) shape conformance with required top-level
  keys (`schema_version`, `spec`, `status`, `name`, `language`,
  `description`, `license`, `artifact`, `spi`, `capabilities`,
  `contract_test`, `provenance`, `signing`) and required signing
  fields (`envelope`, `signature_file`). When a `.sig` is present
  beside a card, the test shells out to `model_signing verify
  sigstore` with the Atmosphere workflow's identity pin and fails
  on any signature mismatch; sigs are skipped silently when absent
  (the normal state on `main` between tagged releases) or when the
  `model_signing` CLI is not installed locally.
  `scripts/scan-skillcards.sh` is the SkillSpector-equivalent
  pre-publish gate — scans every card for prompt-injection markers
  (regex set: "ignore previous instructions", `[INST]`, ChatML role
  tags), hidden Unicode (zero-width chars, Bidi overrides),
  capability-safety violations (TOOL_CALLING ⇒ TOOL_APPROVAL per OWASP
  excessive-agency), missing SPI classes (FQN doesn't resolve to a
  source file on disk), and path-shaped-field safety. HIGH-severity
  findings fail pre-push and the signing workflow, so a compromised
  manifest can never be published as "signed".
  `scripts/validate-capability-claims.sh` runs all three of
  `regen-skillcards.sh --check`, `scan-skillcards.sh --check`, and
  the snapshot freshness check; `scripts/sign-skillcards.sh` +
  `verify-skillcards.sh` are the local CLI wrappers (key / certificate
  / sigstore modes). `regen-skillcards.sh` additionally emits
  `SKILLCARDS.md` at the repo root — the catalog index that lists
  every runtime, its signature state, and links to the card +
  contract test. Distribution model: git itself is the daily sync,
  consistent with the rest of this repository; release-time
  signatures are also attached to the GitHub release as workflow
  artifacts. Pre-push validator regex covers `SKILLCARD.yaml(.sig)?`,
  `(regen|sign|verify|scan)-skillcards.sh`, the signing workflow,
  and `SKILLCARDS.md`. Curated runtime-specific risk and mitigation
  prose remains in each runtime's module README.

## [4.0.47] - 2026-05-21

### Added

- Native Anthropic Messages API runtime in a new `atmosphere-anthropic`
  module (`1195845304`). `AnthropicMessagesClient` posts directly to
  `https://api.anthropic.com/v1/messages`, parses the SSE stream
  (`message_start`, `content_block_start`, `content_block_delta` with
  `text_delta` and `input_json_delta`, `message_delta` carrying
  `usage.input_tokens`/`output_tokens`, `message_stop`), and drives the
  `tool_use` → `tool_result` loop through the shared
  `ToolExecutionHelper.executeWithApproval` (max five rounds,
  cancellation-aware). `AnthropicAgentRuntime` is registered via
  `ServiceLoader` at priority 100 — same posture as every other
  framework runtime — and inherits `executeWithOuterRetry` so it claims
  `PER_REQUEST_RETRY` honestly alongside `TEXT_STREAMING`,
  `SYSTEM_PROMPT`, `STRUCTURED_OUTPUT`, `TOOL_CALLING`, `TOOL_APPROVAL`,
  `TOKEN_USAGE`, `CONVERSATION_MEMORY`, `BUDGET_ENFORCEMENT`,
  `CONFIDENCE_SCORES`, and `PASSIVATION`. `isAvailable()` returns true
  only when `anthropic.api.key` (system property or `AiConfig.LlmSettings`)
  is present, satisfying Correctness Invariant #5 (Runtime Truth). The
  capability snapshot, capability matrix, and `modules/ai/README.md`
  prose were regenerated in the same commit; runtime count is now ten.
- LangChain4j 1.15.0 parity (`9e72c6c6f7`): tool-error fallback in
  `ToolExecutionHelper.executeWithApproval` wraps null/blank exception
  messages with the throwable's simple class name so NPEs surface as
  `{"error":"NullPointerException"}` to the model instead of opaque
  `{"error":"null"}`. Custom HTTP headers on `OpenAiCompatibleClient`
  carry proxy / per-tenant / tracing metadata (Helicone, OpenRouter, …);
  reserved names (`Authorization`, `Content-Type`, `Accept`) are
  filtered at request-build time. `AgentFleet.vote(...)` adds consensus
  dispatch — fans every call out in parallel and returns the result
  whose normalized text (`strip().toLowerCase(Locale.ROOT)`) is shared
  by the most peers, with deterministic insertion-order tie-breaking
  and a synthetic `"vote"` failure when every peer fails.
- JFR observability, declarative tool permissions, first-run sub-agent
  guard, and episodic memory (`63e34f11a4`):
    - JFR events under `org.atmosphere.ai.jfr` — `AiCallEvent`,
      `AgentTurnEvent`, `ToolInvocationEvent`, `SubAgentDispatchEvent`,
      `EpisodicMemoryAccessEvent`, `SessionLifecycleEvent`,
      `AiErrorEvent` — emitted by `JfrAiMetrics`. `CompositeAiMetrics`
      lets JFR coexist with Micrometer (and any other backend) instead
      of one replacing the other.
    - `ToolPermissionPolicy` SPI with the `PropertiesToolPermissionPolicy`
      reference impl: declarative ALLOW/DENY rules layered on top of the
      existing `@RequiresApproval` annotation gate. Reachable from
      `ToolExecutionHelper` so every runtime inherits it.
    - First-run sub-agent dispatch guard in `DefaultAgentFleet` — the
      bootstrap pass sequences the first call through every peer
      individually before the parallel fan-out path is unlocked, so a
      cold-cache misconfiguration is caught against one peer instead of
      fanned out N× simultaneously.
    - `EpisodicMemoryStore` SPI with `InMemoryEpisodicMemoryStore` and
      `JsonFileEpisodicMemoryStore` backends, the `MemoryEntry` /
      `EpisodicMemoryQuery` / `EpisodicMemoryType` records, and the
      `EpisodicMemoryAccessEventBridge` JFR fan-out.

### Changed

- `GrpcWasyncTransportTest` status-poll widened from 5 s to 20 s and
  the `@Timeout` from 10 s to 30 s for JDK 26 scheduler variance
  (`df19027ab8`). Closes a recurring "Core (JDK 21/26)" flake.

### Fixed

- `samples/spring-boot-coding-agent`: case-insensitive README probe
  (`38565e43ef`). Repos like `sindresorhus/awesome` ship `readme.md`
  lowercase, which the old `README.md`-only lookup missed silently. The
  redundant `application.properties` and the unused
  `atmosphere.ai.runtime` setting were also dropped — the sample is
  deterministic and never invokes an LLM.

## [4.0.46] - 2026-05-16

### Added

- Spring AI Alibaba: unconditional `TOOL_CALLING` / `TOOL_APPROVAL` /
  `TOKEN_USAGE` (`534317f03d`) — `UsageCapturingChatModel` wraps the
  configured Spring AI `ChatModel` bean at auto-configuration time;
  per-thread accumulator captures `ChatResponseMetadata.getUsage()` across
  every step of the ReAct graph and emits a single
  `session.usage(TokenUsage)` after each dispatch. Tool calling is no
  longer gated on `staticChatModel != null` — `SpringAiAlibabaToolBridge`
  is wired on every dispatch with tools, and the runtime fails fast with
  `configurationHint()` if `ChatModel` is missing. Closes the last
  conditional capability gap from the runtime parity push (`62a9b7e6af`).
- RAG vector-store matrix expanded with three direct connectors
  (`31d6455a75`): `PgVectorContextProvider` (Postgres + pgvector via
  JDBC), `QdrantContextProvider` (Qdrant REST over
  `java.net.http.HttpClient`), and `PineconeContextProvider` (Pinecone
  REST). Each connector embeds the user query through
  `EmbeddingRuntime`, validates caller-controlled identifiers at
  construction time per Boundary-Safety invariant, and ships with a
  Mockito-backed unit-test suite. `modules/rag/README.md` adds a
  reachability matrix showing the six direct providers plus the
  Spring AI / LangChain4j bridges covering the long tail (Weaviate,
  Milvus, Chroma, Elasticsearch, Redis Stack, MongoDB Atlas, OpenSearch,
  Cassandra).
- Workflow authoring inside the admin control plane (`81ff454177`) —
  `WorkflowManifest` JSON record, `WorkflowStore` SPI with
  `InMemoryWorkflowStore` default and optimistic-concurrency version
  conflict detection, `WorkflowController` with `ControlAuthorizer`
  gating plus audit-log entries on every save / delete, Spring Boot
  endpoint exposes `GET/POST/DELETE /api/admin/workflow`, and
  `/atmosphere/admin/workflow.html` ships a vanilla-JS authoring UI
  that lists / creates / edits manifests against the REST surface.
- Eval dashboard inside the admin control plane (`38e2a45920`) —
  `EvalRun` JSON record, `EvalRunStore` SPI with bounded-ring-buffer
  `InMemoryEvalRunStore` default (500 runs per baseline,
  oldest-evicted), `EvalController` aggregates pass-rate per baseline,
  Spring Boot endpoint exposes
  `GET/POST/DELETE /api/admin/evals/{runs,baselines}`, and
  `/atmosphere/admin/evals.html` surfaces pass-rate meters + recent-run
  table with auto-refresh. CI submits a JSON body per LLM-as-judge run
  and the dashboard surfaces the trend without leaving the control
  plane.
- `atmosphere-admin-bundle` enterprise console aggregator
  (`eaad0df089`) — single Maven `pom`-packaging artifact that
  transitively pulls in `atmosphere-spring-boot-starter`,
  `atmosphere-admin`, `atmosphere-ai`, `atmosphere-coordinator`,
  `atmosphere-agent`, `atmosphere-rag`, `atmosphere-checkpoint`,
  `atmosphere-durable-sessions`, and
  `atmosphere-durable-sessions-sqlite`. Adding one dep gives operators
  the dashboard, journal flow viewer, workflow authoring, eval
  dashboard, and governance decision viewer; deliberately does not pin
  an `AgentRuntime` adapter or a vector-store driver so operators
  choose those independently.
- `docs/runtime-selection.md` (`97130eeeeb`) — nine-runtime decision
  tree walking the questions an architect should answer before picking
  an `AgentRuntime`, cross-referenced against the pinned capability
  snapshot. Companion to the cli / samples "flagship enterprise
  templates" promotion that calls out `rag`, `ai-tools`,
  `guarded-agent`, `coding-agent`, and `ms-governance` as the canonical
  agent shapes.

### Tests

- AI gap Playwright coverage (`e91b8084fd`) — `ai-gap-coverage.spec.ts`
  exercises the deterministic RAG, input-assembly telemetry, and evaluator
  artifact paths through `AiFeatureTestServer`; Vue, Svelte, and React Native
  hook tests pin the new chat-hook parity surface.
- `UsageCapturingChatModelTest`, `SpringAiAlibabaRuntimeContractTest`
  (TC/TA/TU pinned), `WorkflowManifestTest`, `WorkflowControllerTest`,
  `EvalControllerTest`, `PgVectorContextProviderTest`,
  `QdrantContextProviderTest`, `PineconeContextProviderTest`
  (`534317f03d`, `31d6455a75`, `81ff454177`, `38e2a45920`).

## [4.0.45] - 2026-05-12

### Added

- capability-matrix snapshot + drift gate (`d22d18a7cd`) — new `.harness/capabilities.snapshot.json` is the canonical aggregate of the `AiCapability` enum (20 capabilities) and each runtime's pinned `expectedCapabilities()` (9 runtimes). Regenerated by `scripts/regen-capability-snapshot.sh` and validated by both `scripts/validate-capability-claims.sh` (wired into `scripts/pre-push-validate.sh` Tier 1) and the new `CapabilitySnapshotTest` in `modules/ai-test`. The per-runtime contract tests already pin per-runtime drift; this layer pins aggregate count claims in `modules/ai/README.md` against the snapshot so prose like "All 9 runtimes…" cannot drift from the running code without breaking the build. Adds a "What capability flags do *not* claim" disclosure block to `modules/ai/README.md` § Adapter Runtimes covering implementation parity, limit numbers, provider-side guarantees, and production fitness — the four edges callers commonly assume from a capability flag and that the flag does not promise.
- drift-log + Stop-hook enforcement (`c685f9588f` + `4f6a51d3a8`) — new `.harness/drift-log.md` is the append-only record of every agent claim that diverged from ground truth (code, git history, runtime state). Two enforcement points: `scripts/validate-drift-log.sh` checks structural hygiene (chronological sections, no future dates, prior-section append-only invariant against `origin/main`) and is wired into pre-push Tier 1; `.claude/hooks/check-drift-log.sh` is a Claude Code Stop hook (registered in `.claude/settings.json`) that greps the session transcript for high-precision drift-correction patterns and blocks session end if the log was not modified. Together with the capability snapshot, this is the **verification** rail of the harness pattern documented by Anthropic ([*Effective harnesses for long-running agents*](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)) and OpenAI ([*Harness engineering*](https://openai.com/index/harness-engineering/)), applied to AI prose claims about this repo — measuring change failure rate by agent claim, not utilization. Operator notes live in `.harness/README.md`.

### Fixed

- AiPipeline input-assembly snapshot stale across guardrails+policies (`f16e9da396`) — the per-stage telemetry snapshot of `request.systemPrompt()` was captured before guardrails (`AiGuardrail.GuardrailResult.Modify`) and governance policies (`PolicyDecision.Transform`) had a chance to mutate the request. The runtime later executed the post-mutation request, so the system stage was under- or over-reported. Snapshot moved to after both loops have run, before pipeline-driven augmentations (structured-output schema, confidence cue) which are tracked separately. Regression test in `AiPipelineInputAssemblyTest`.
- BudgetCapturingSession wall-clock callback-lazy enforcement (`f16e9da396`) — wall-clock cap was sampled only at session-method boundaries, so a provider that hung silently after dispatch never tripped. Now the deadline is scheduled up-front via a daemon `ScheduledExecutorService`; on trip the decorator both routes the typed `AiBudgetExceededException` through `session.error` and fires a pipeline-supplied `onTrip` hook bound to `runtime.executeWithHandle`'s `handle::cancel`, so the in-flight runtime call is cancelled instead of left dangling. The task is cancelled on `complete()` / `complete(summary)` / `error()` so the scheduler thread is freed promptly. Existing wall-clock assertion loosened from `>` to `>=` (`09b2d2b610`) because the scheduled task can fire exactly at the deadline. Regression test (`wallClockBudgetTripsWhenRuntimeMakesNoSessionCallbacks`) covers the no-callback path.
- McpToolSource transport leak on initialize/listTools failure (`f16e9da396`) — `connect(transport, label)` constructed an `McpClient` and called `initialize()` + `listTools()` without a failure cleanup path, leaking the underlying transport (subprocess pipes, sockets, HTTP connection pool) for the JVM lifetime if either threw. Wrapped in try/catch with `client.closeGracefully()` on failure (Ownership Invariant #1). Regression test stubs an `McpClientTransport` whose `connect()` returns `Mono.error` and asserts `closeGracefully` was called.
- AgentPassivation responseType ignored on resume (`f16e9da396`) — `AgentSnapshot` persisted `responseTypeName` but `resume()` rebuilt the context from `base.responseType()`, so a structured-output agent resumed with a base context that lacked the type lost typed parsing despite the snapshot carrying the field. New `resolveResponseType` helper resolves the snapshot's class name via the thread-context classloader (with fallback to `base.responseType()` plus a one-line WARN if the class is not on the resumer's classpath). Regression test in `AgentPassivationTest`.
- off-by-one in `modules/ai/README.md` § ToolLoopPolicy "the other six runtimes" → "the other seven runtimes". With Built-in and Koog handling `COMPLETE_WITHOUT_TOOLS` natively and 9 runtimes total, the count of "other" runtimes is 7, not 6. Surfaced by the new capability-matrix verification work.

### Documentation

- `atmosphere.github.io` capability claims aligned with the snapshot. Cross-validated against `.harness/capabilities.snapshot.json`; fixed `docs/.../reference/ai.md` TOOL_APPROVAL list (Embabel and SK were wrongly excluded), `docs/.../tutorial/26-foundation-primitives.md` gateway-consumer count ("seven of nine" → all nine — every runtime calls `admitThroughGateway`), added 5 missing capability rows (`BUDGET_ENFORCEMENT`, `CONFIDENCE_SCORES`, `PASSIVATION`, `MODEL_ENUMERATION`, `MULTI_AGENT_HANDOFF`) to `tutorial/11-ai-adapters.md`, and refreshed four pre-Koog/SK/AgentScope/Alibaba narrative enumerations to the current 9-runtime set. Website main: `13fe8c4`.
- `.harness/README.md` — operator manual for the harness directory (canonical files, validators, hooks, regen + append protocols).

## [4.0.44] - 2026-05-08

### Added

- predictable-AI primitives — three framework-level capabilities that close gaps Bonér's "Herding LLMs" deck flagged for distributed-system reliability, all declared on every framework runtime so the matrix closes without `@Beta` shims:
  - `BUDGET_ENFORCEMENT` (`a4fae39464`) — new `AiBudget` value record (max input / output / total tokens, max steps, max wall clock) installed via `pipeline.setDefaultBudget(...)` or per-request `ai.budget` metadata. `BudgetCapturingSession` decorator slots into the AiPipeline session-decorator stack between metrics and guardrail layers; on breach it routes a typed `AiBudgetExceededException extends AiException` through `session.error(...)` and short-circuits subsequent `send` / `usage` / `progress` / `emit` / `complete` calls so the wire protocol's "one terminal frame" invariant holds. Distinct from `org.atmosphere.ai.budget.StreamingTextBudgetManager` (long-running per-tenant cumulative spend); this capability is the per-call death-spiral guard. 13 new unit tests cover every breach reason, default vs. per-request override, and the post-trip swallow. Wall-clock limits trip universally; token / step limits depend on `TOKEN_USAGE` (every runtime except Spring AI Alibaba honors both).
  - `CONFIDENCE_SCORES` (`a4fae39464`) — new `AiConfidence` record with `OptionalDouble aggregate`, `List<TokenLogprob>` tokens, and `Source` enum (`LOGPROBS_NATIVE` / `MODEL_REPORTED_FIELD` / `HEURISTIC`). `StreamingSession.confidence(AiConfidence)` default method auto-emits `ai.confidence.aggregate` / `.source` / `.tokens` metadata mirroring the `usage(TokenUsage)` convention; `DelegatingStreamingSession` gains the matching forwarding override. Universal model-reported path via the new `AiConfidenceElicitation` plus the `ConfidenceCapturingSession` decorator: pipeline appends an opt-in cue to the system prompt, decorator parses the model-emitted `{"confidence": 0.x}` field on stream completion (same regex shape as the existing `ConfidenceThresholdGuardrail`) and fires `session.confidence` ahead of the terminal frame. Decorator self-suppresses when a runtime already invoked `confidence(...)` directly with `LOGPROBS_NATIVE`. Skipped when structured-output mode is in play because the schema parser owns the response shape — callers add a `confidence` field to their record schema in that mode. 14 new unit tests cover record validation, the elicitation cue, parser fallbacks, runtime-explicit override, per-request override of the pipeline default, and the structured-output skip.
  - `PASSIVATION` (`a4fae39464`) — new `AgentSnapshot` record in `modules/ai` (persistable subset of `AgentExecutionContext` — message, system prompt, identity columns, history, JSON-clean metadata, response type name, reason, paused-at). New `AgentPassivation` static helper in `modules/checkpoint` with `passivate(runtime, ctx, store, reason): String`, `resume(runtime, store, id, externalSignal, base, session)`, and `loadSnapshot(store, id)`. `resume` merges the snapshot onto a caller-supplied base context (which carries the runtime references — tools, memory, listeners, retry policy — that don't survive a JVM restart) and re-runs `runtime.execute(...)`; base wins on metadata-key collision so caller-injected refs (e.g. trace context) are not clobbered by stale snapshot values. Helper lives in `modules/checkpoint` rather than on `AgentRuntime` itself because `modules/ai → modules/checkpoint` introduces a `ai → checkpoint → coordinator → ai` cycle; the reverse direction is acyclic. Capability flag declared on every runtime — flag advertises "this runtime cooperates with `AgentPassivation`," honest because every runtime threads `context.history()` through its dispatch path so a resumed call observes the same conversation the paused call saw. 10 new unit tests cover snapshot round-trip, resume flow with external signal, signal-less replay of pending message, missing-checkpoint errors, metadata filtering (non-String values dropped pre-snapshot), metadata merge precedence, unique IDs across passivations, and null-arg rejection.

### Changed

- `AiCapability` enum gains 3 entries — total 20 capabilities (was 17). `AbstractAgentRuntimeContractTest.expectedCapabilities()` pin updated for all 9 framework runtimes: BuiltIn, Spring AI, LangChain4j, ADK, Embabel, Koog, AgentScope, Spring AI Alibaba, Semantic Kernel. `BuiltInAgentRuntime` capability count test bumped from 13 to 16. Capability matrix in `modules/ai/README.md` extended with `BE` / `CS` / `PSV` columns plus a "Predictable-AI primitives" section documenting each capability's decorator placement, source enum, and runtime-by-runtime caveats (Spring AI Alibaba's `BUDGET_ENFORCEMENT` is wall-clock-only because the runtime does not surface `TOKEN_USAGE`).

### Tests

- Playwright e2e coverage for the predictable-AI primitives (`4be20c240c`) — `ai-budget-circuit-breaker.spec.ts`, `ai-confidence-elicitation.spec.ts`, `ai-passivation.spec.ts` exercise the full `AiPipeline` session-decorator stack through Atmosphere's wire transport. Each spec drives a dedicated test handler (`BudgetCircuitBreakerTestHandler` / `ConfidenceElicitationTestHandler` / `PassivationTestHandler`) registered in `AiFeatureTestServer` so the harness sees `AiBudgetExceededException` on the wire's error frame, the `ai.confidence.aggregate` / `.source` / `.tokens` metadata frames the `confidence(...)` default sink emits, and the snapshot/resume round-trip across two sequential WebSocket connections. `modules/integration-tests/pom.xml` bumps `atmosphere-checkpoint` from test to compile scope so `PassivationTestHandler` can call `AgentPassivation` directly. `modules/integration-tests/playwright.config.ts` registers the three new spec project entries.

### Fixed

- CLI standalone-scaffold compile against the released parent POM (`7383eb0ee2`) — twenty `samples/spring-boot-*/pom.xml` files now declare `<netty.version>4.2.13.Final</netty.version>` in their own `<properties>` block so the sample is self-contained: scaffold-then-compile against the released `org.atmosphere:atmosphere-project:4.0.43` parent (which predates the netty bump and therefore does not declare `netty.version`) no longer fails with `Non-resolvable import POM: io.netty:netty-bom:pom:${netty.version}`. `cli/e2e-test-cli-runtime.sh` also fixes a long-standing comment-vs-code drift: the script was exporting `ATMOSPHERE_CLI_VERSION` while `cli/atmosphere` only ever read `ATMOSPHERE_VERSION_OVERRIDE`, so the SNAPSHOT-against-SNAPSHOT lane silently degraded to release-pin coverage. Renaming the export aligns the script with the CLI's actual contract.

## [4.0.43] - 2026-05-06

### Added

- per-request runtime extension helpers — small `attach(context, ...)` / `from(context)` helpers (modeled on the existing `CacheHint`) that let callers stash framework-native composition objects in `AgentExecutionContext.metadata()`, so a runtime can apply them per-request without growing the unified `AgentRuntime` SPI with framework-specific knobs. The matrix closes on 4.0.43: all eight framework runtimes have a sidecar — `SpringAiAdvisors` (Spring AI `Advisor` chain — RAG / memory / guardrails / observability), `LangChain4jAiServices` (LangChain4j `AiServices` / `TokenStream`), `KoogStrategy` (Koog `AIAgentGraphStrategy` DSL), `AdkRootAgent` (ADK `BaseAgent` / `SequentialAgent` / `ParallelAgent` / `LoopAgent` topology), `EmbabelPromptRunner` (`UnaryOperator<PromptRunner>` customizer applied AFTER default wiring), `AgentScopeAgent` (per-request `ReActAgent`), `SemanticKernelInvocation` (per-request `InvocationContext` — unlocks `KernelHooks`, `withMaxAutoInvokeAttempts`, custom `PromptExecutionSettings`), `SpringAiAlibabaRunnableConfig` (per-request Alibaba `RunnableConfig` for `threadId`/`checkPointId`/`streamMode`/`metadata`/`store`), plus the cross-runtime `ToolLoopPolicies` honored by `BuiltInAgentRuntime`'s OpenAI-compatible tool loop. Initial five sidecars landed via `f1493c3f9c`; the remaining four runtimes plus the lifecycle hook fan-out below landed via `eec98890fe`. Also added: `AgentLifecycleListener.onModelStart` / `onModelEnd` / `onModelError` hooks with `fireXxx` fan-out helpers wired in all 8 framework runtimes (was Built-in only), and `AiEventForwardingListener` adapter that translates lifecycle hooks to wire-format `AiEvent.Progress` frames (opt-in via `context.withListeners(...)`). Each bridge ships with a unit-level `*BridgeTest` proving the runtime honors the sidecar.

### Fixed

- Quarkus extension closes the `/api/console/info` parity gap — the bundled Atmosphere Console UI gets the same `subtitle / endpoint / runtime / mode` payload it gets from the Spring Boot starter, instead of falling through to the Vue defaults on a 404. New `AtmosphereConsoleInfoServlet` (HttpServlet, registered at build time via a second `ServletBuildItem` mapped to `/api/console/info`) reuses the same package-prefix mode-detection heuristic as `AtmosphereConsoleInfoEndpoint` (`org.atmosphere.{ai,agent,coordinator}.*` → `"ai"`, anything else including `ManagedAtmosphereHandler` → `"broadcast"`). Endpoint auto-detection prefers the canonical `/atmosphere/ai-chat` when registered (samples like `quarkus-ai-chat` ship multiple `@AiEndpoint`s), then `/atmosphere/agent/*`, then any other `/atmosphere/*`. New config keys `quarkus.atmosphere.console-subtitle` and `quarkus.atmosphere.console-endpoint` mirror the Spring `atmosphere.console-subtitle` / `atmosphere.console-endpoint` properties. JSON is hand-rolled so the runtime POM stays Jackson-free; `AgentRuntimeResolver` is reached via reflection so the servlet keeps no compile-time link to `modules/ai`. Empirically verified in chrome-devtools against `quarkus-ai-chat` — `/api/console/info` now returns `{"subtitle":"Runtime: langchain4j","endpoint":"/atmosphere/ai-chat","runtime":"langchain4j","mode":"ai"}`, the Vue Console shows the runtime label in the header subtitle, and the cross-tab isolation matrix continues to pass on the Quarkus leg.
- bundled Atmosphere Console now auto-detects AI vs. broadcast endpoints (`c1e8e36c7b`) — `/api/console/info` adds a `mode` field (`"ai"` for `@AiEndpoint` / `@Agent` / `@Coordinator`, `"broadcast"` for `@ManagedService` chats); `AtmosphereConsoleInfoEndpoint#detectMode` classifies via the registered handler's package prefix (`org.atmosphere.{ai,agent,coordinator}.*` → ai, everything else including `ManagedAtmosphereHandler` → broadcast), so the check stays compile-time independent of `modules/ai` and `modules/agent`. The Vue frontend swaps empty-state copy ("Start a conversation" + "AI assistant" → "Start a broadcast" + "every connected client on this endpoint will receive it") and the default subtitle ("Runtime: <name>" → "Multi-client broadcast chat") based on the detected mode. Closes the misleading-UI half of the cross-tab leak follow-up: pre-fix, `spring-boot-mcp-server` and `spring-boot-otel-chat` rendered the AI-assistant copy despite being broadcast-shared by design. Empirically verified in chrome-devtools against both broadcast samples and `spring-boot-ai-chat`. Per-sample `atmosphere.console-subtitle` overrides still win over the mode-aware default. 5 new contract tests in `AtmosphereConsoleInfoEndpointModeTest` pin the four classification paths plus the override interaction.
- cross-tab isolation matrix extended from 11 → 15 samples (`c1e8e36c7b`) — adds `quarkus-ai-chat` (cross-runtime parity, proves the targeted-dispatch fix from `1fbb0958f0` survives Quarkus's distinct `QuarkusJSR356AsyncSupport` path), `spring-boot-checkpoint-agent` (`@Coordinator` with analyzer/approver fleet + checkpoint store), `spring-boot-ms-governance-chat` (`@AiEndpoint` with `@AgentScope` classification interceptors stacked in front), and `spring-boot-channels-chat` (omnichannel `@AiEndpoint` with Telegram/Slack/WhatsApp/Messenger channel-bridge adapters). New fixture entries in `e2e/fixtures/sample-server.ts` for the checkpoint and ms-governance samples; quarkus-ai-chat reuses the existing fixture. Out-of-scope docstring corrected to remove `spring-boot-channels-chat` (it's actually an isolated `@AiEndpoint`, not a broadcast chat) and to explicitly tag `spring-boot-a2a-agent` as out-of-scope (A2A JSON-RPC has no two-tab Console scenario). All 15 cases pass locally in 3.5m.
- `OpenAiCompatibleClient` Javadoc placement broke JDK 26 strict mode (`28703ea064`) — two stacked Javadoc blocks on `forwardResponsesApiUsage` raised `documentation comment is not attached to any declaration` under `-Xlint:all` `-Werror` on JDK 26 (silent on JDK 21). Merged into a single coherent block; CI: Core (JDK 21/26) green.
- `cli/samples.json` and `cli/atmosphere` template map referenced deleted `spring-boot-embabel-chat` sample (`3a9373e875`) — `cli/test-cli.sh` failed with "samples missing README.md: spring-boot-embabel-chat". Entry removed from samples.json + template-map case statement.

## [4.0.42] - 2026-05-01

### Added

- atmosphere-verifier — plan-and-verify (Meijer "Guardians of the Agents") New module modules/verifier/ + sample samples/spring-boot-guarded-email-agent/ — sealed Workflow AST, ServiceLoader-discovered PlanVerifier chain (Allowlist/WellFormed/Capability/Taint/Automaton/SmtChecker SPI), @Sink + @RequiresCapability scanners, PlanAndVerify orchestrator, WorkflowExecutor with partial-env on failure, verify CLI; sample REST + UI exercises the inbox-exfiltration scenario end-to-end (refused before any tool fires) — 74 unit + 4 boot + 6 Playwright tests, all CI green on the feature branch.

### Fixed

- fail-closed verifier empty-chain, JSON-escape govern. deny, deflake wasync PlanAndVerify.withDefaults + VerifyCli runChain throw / emit chain-empty violations when ServiceLoader yields no providers (P1: silent fail-open under shading / native-image / fat-jar relocation); governance-deny tool result routes every interpolated field through ToolBridgeUtils.escapeJson via a new buildGovernanceDenyJson helper (P2: backslash/newline/control char break); ChatIntegrationTest.socketStatusTransitions polls for status transition rather than asserting in the same instant the OPEN handler fires (release-pipeline timing flake). 5 new verifier tests + 6 governance-JSON tests.

### Changed

- drop org.json:json — Jackson 3 only (CVE hygiene) RoomProtocolCodec + SimpleRestInterceptor migrated to tools.jackson; brace-balanced reader preserves SwaggerSocket header/body chunk semantics; ALLOW_SINGLE_QUOTES kept for wire compatibility; org.json removed from parent + 3 spring-boot samples.
- bump version to 4.0.41
- prepare for next development iteration 4.0.42-SNAPSHOT

## [4.0.41] - 2026-04-29

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
