# Sample matrix — sweep order, ports, and headline assertions

33 samples. `samples/shared-resources` is a resource pack, not a sample.

**Two surfaces are not in this table** and have their own passes:
`samples/spring-boot-ai-classroom/expo-client/` (Step 1b, `expo-sweep.md` —
a native app, not a Maven module, absent from `cli/samples.json`) and the
`atmosphere` CLI (Step 1c, `cli-sweep.md`).

**Verify the count before you trust this file.** Sources of truth:

```bash
ls -d samples/*/ | grep -v shared-resources | wc -l
python3 -c "import json;d=json.load(open('cli/samples.json'));print(len(d['samples']))"
```

If they disagree with the 33 rows here, the matrix is stale — reconcile it
first (and `scripts/release-gate-samples.sh` has a drift gate that fails when a
sample directory has no coverage entry, so check there too).

## Sweep ports

The 9101+ block is used so the sweep never collides with the samples' own
defaults (many are 8080) or the Playwright fixture's 8080–8104
(`modules/integration-tests/e2e/fixtures/sample-server.ts`). `quarkus-ai-chat`
keeps 18810 because its `application.properties` pins `quarkus.http.port`.

## Boot types

`scripts/sweep-sample.sh` detects these; the column is here so you know what to
expect and can boot by hand if needed.

| Type | Detection | Command | Port flag |
|---|---|---|---|
| `spring-boot` | manifest has `Spring-Boot-Classes` | `java -jar target/<boot>.jar` | `--server.port=N` |
| `quarkus` | `target/quarkus-app/quarkus-run.jar` | `java -jar target/quarkus-app/quarkus-run.jar` | `QUARKUS_HTTP_PORT=N` |
| `main-jar` | shaded jar with plain `Main-Class` | `java -Dserver.port=N -jar <jar>` | `-Dserver.port=N` |
| `war` | `<packaging>war</packaging>` | `./mvnw -B jetty:run` in the sample dir | `-Djetty.port=N` |
| `exec` | no runnable artifact, pom declares `exec-maven-plugin` | `./mvnw exec:java` in the sample dir | `-Dhttp.port=N` and `-Dserver.port=N` both passed |

## Common env

```bash
# streaming samples
--env LLM_MODE=local --env LLM_API_KEY=ollama --env LLM_MODEL=qwen2.5:3b
# tool-heavy agents
--env LLM_MODE=local --env LLM_API_KEY=ollama --env LLM_MODEL=qwen2.5:7b-instruct-q4_K_M
```

`LLM_API_KEY=ollama` is a required placeholder, not decoration. The launcher
scrubs ambient LLM env, and with **no** key the framework correctly takes its
keyless demo path and streams a canned response — which is a valid PASS for
`one-dep-agent`'s keyless headline but proves nothing about a real LLM turn.
Verified 2026-08-07: without the placeholder the Console renders "Demo mode —
this response is a canned placeholder"; with it, real Ollama content arrives.

`LLM_MODE=local` resolves the base URL to `AiConfig.OLLAMA_ENDPOINT`
(`http://localhost:11434/v1`). It only governs the **built-in / Spring** path —
`quarkus-ai-chat` configures LangChain4j directly and needs explicit
`LLM_BASE_URL` (see its row).

---

## Chat & transports

| # | Sample | Port | Boot | `--ready-path` | Drive | Headline assertion |
|---|---|---|---|---|---|---|
| 1 | `spring-boot-ai-chat` | 9101 | spring-boot | `/atmosphere/console/` | Console | Real Ollama token stream renders incrementally; live token metrics update. Drive out-of-box posture (the sample ships `atmosphere.auth.enabled=false`; only the e2e fixture forces auth on) |
| 2 | `spring-boot-chat` | 9102 | spring-boot | `/atmosphere/console/` | Console | Broadcast round-trip: the message sent is rendered back to the subscriber |
| 3 | `embedded-jetty-websocket-chat` | 9103 | main-jar | `/` | Console | No-framework Jetty; WS broadcast echoes back author-prefixed |
| 4 | `quarkus-chat` | 9104 | quarkus | `/` | Console | Quarkus WS broadcast round-trip |
| 5 | `chat` (WAR) | 9105 | war | `/` | Console | `@ManagedService` WAR on Jetty (JSR-356) round-trip. Slowest boot — Maven resolves before Jetty starts |
| 6 | `grpc-chat` | 9106 | exec (`-Dhttp.port=9106`; gRPC listener defaults to 9090) | `/` | Console | Transport badge reads `Connected · grpc` (Connect protocol, JSON mode) and a message round-trips. `runnable=false` in `cli/samples.json` — Maven boot is expected, not a finding |
| 7 | `kotlin-dsl-chat` | 9107 | main-jar | `/chat` | headless WS + POST | `ping` → `pong` delivered over a real WebSocket, **and** zero SLF4J NOP-logger lines in the server log (both were the 2026-06 PARTIAL). **Use the 7b model** — the agent registers a `word_count` tool, and qwen2.5:3b tool-calling yields a silent empty completion (2026-08-22) |

## Agents — built-in and single-dependency

| # | Sample | Port | Boot | `--ready-path` | Drive | Headline assertion |
|---|---|---|---|---|---|---|
| 8 | `spring-boot-one-dep-agent` | 9108 | spring-boot | `/atmosphere/console/` | Console | One Atmosphere dep + one `@Agent` streams a keyless reply |
| 9 | `spring-boot-dentist-agent` | 9109 | spring-boot | `/atmosphere/agent/dentist` | Console | LangChain4j + Ollama returns structured dental-emergency guidance. Slack/Telegram channels need tokens — Web channel only |
| 10 | `spring-boot-orchestration-demo` | 9110 | spring-boot | `/atmosphere/agent/support` | Console | Support agent drives the refund flow **through a tool turn** — this is the sample that exposed the langchain4j skew, so a tool turn is mandatory, not optional |
| 11 | `spring-boot-ms-governance-chat` | 9111 | spring-boot | `/atmosphere/ms-governance` | Console | All governance policies evaluate with provenance + timing; ADMIT/DENY rendered. Deterministic — rules short-circuit before the LLM |
| 12 | `spring-boot-guarded-email-agent` | 9112 | spring-boot | `/atmosphere/console/` | Console → Validation tab | A malicious goal is REFUSED by the taint verifier **before any tool fires**; fail-closed auth returns 401 without a token. Deterministic |
| 13 | `spring-boot-personal-assistant` | 9113 | spring-boot | `/atmosphere/agent/primary-assistant` | Console | Long-term memory: store a fact, then recall it in a later turn via tools → VFS |
| 14 | `spring-boot-multi-agent-startup-team` | 9114 | spring-boot (`--jvm-arg -Datmosphere.admin.content-read-auth-required=false` to read the Tape tab) | `/atmosphere/agent/ceo` | Console | `@Coordinator` dispatches to 4 A2A agents; coordination journal + tool calls render. Tool-heavy — use the 7b model, and classify a small-model failure as a model limitation, not a regression |
| 15 | `spring-boot-ai-tools` | 9115 | spring-boot | `/atmosphere/ai-chat` | Console | HITL gate **intercepts** the approval-required tool; Approve → tool executes and the result renders |
| 16 | `spring-boot-rag-chat` | 9116 | spring-boot | `/atmosphere/console/` | Console | `search_knowledge_base` grounds the answer in the ingested corpus (a real Spring AI `VectorStore`, not a map) |

## Agent protocols (headless — driven over the wire)

These serve no HTML. Driving them over their wire protocol *is* the analog to
browser-driving; it is not a curl exemption for UI samples.

| # | Sample | Port | Boot | `--ready-path` | Drive | Headline assertion |
|---|---|---|---|---|---|---|
| 17 | `spring-boot-a2a-agent` | 9117 | spring-boot | `/` | A2A JSON-RPC | Agent Card discoverable at `/.well-known/agent.json`; `message/send` → `TASK_STATE_COMPLETED` with a real artifact |
| 18 | `spring-boot-mcp-server` | 9118 | spring-boot | `/atmosphere/chat` | MCP streamable HTTP | `initialize` + `tools/list` enumerates the tools + `tools/call` returns a **runtime-resolved** payload (Invariant #5: version must come from the running framework, not a constant) |
| 19 | `spring-boot-passivation-agent` | 9119 | spring-boot | `/` | REST | pause → inspect → resume: history restored, signal applied, `continued:true` |
| 21 | `spring-boot-reattach-harness` | 9121 | spring-boot | `/atmosphere/agent/harness/` | REST + WS replay | run-id reconnect drains **all** buffered events in order (server logs `replayed N/N`) |

## Infrastructure & integration

| # | Sample | Port | Boot | `--ready-path` | Drive | Headline assertion |
|---|---|---|---|---|---|---|
| 20 | `spring-boot-durable-sessions` | 9120 | spring-boot | `/atmosphere/chat` | Console | `@ManagedService` + SQLite; WS broadcast round-trip |
| 22 | `spring-boot-spring-ai-advisors` | 9122 | spring-boot | `/atmosphere/console/` | Console | A bound Spring AI `ChatClient` keeps its `defaultAdvisors`; the advisor audit log shows the chain fired |
| 23 | `spring-boot-agui-chat` | 9123 | spring-boot | `/atmosphere/agent/assistant` | Console (`· ag-ui`) | A tool call streams as AG-UI SSE events and the answer is derived from the tool result |
| 24 | `spring-boot-channels-chat` | 9124 | spring-boot | `/atmosphere/ai-chat` | Console | Web channel round-trips through the omnichannel agent. External channels need tokens — PARTIAL on those is expected and must be named |
| 25 | `spring-boot-ai-classroom` | 9125 | spring-boot | `/atmosphere/classroom/general` | Console (`· webtransport`) | Room join over WebTransport; the shared AI stream is delivered to the room |
| 26 | `spring-boot-coding-agent` | 9126 | spring-boot | `/actuator/health` | Console → Interactions tab (or `POST /api/interactions`) **and** Console → Chat | **Two independent surfaces.** (1) Durable-step timeline: launch a background run from the Interactions tab / `POST /api/interactions {"message":…,"background":true}`, assert COMPLETED with its step timeline + metadata panel. A chat prompt never creates an interaction — `GET /api/interactions` staying `[]` after chat drives is correct, not a defect. (2) Sandbox: the Docker container is provisioned by `@SandboxTool` on the **deterministic** `@Prompt` body (no LLM, no model-invoked tool) on the **chat** path only, and is `docker rm -f`'d when the prompt returns — so a post-hoc `docker ps -a` can never show it. Capture container evidence **live** (`docker events`, or poll `docker ps -a --filter name=atmo-sandbox` during the run). `docker ps -a --filter since=10m` is an invalid query — `since` takes a container ref and the daemon answers `No such container: 10m` |
| 27 | `spring-boot-otel-chat` | 9127 | spring-boot | `/atmosphere/ai-chat` | Console | Broadcast round-trip **and** spans emitted on connect/message. OTLP export needs a collector — absent one, assert spans are produced, not exported |
| 28 | `spring-boot-checkpoint-agent` | 9128 | spring-boot | `/atmosphere/agent/dispatch` | Console + checkpoint panel | `@Coordinator` dispatch creates a **durable checkpoint** visible in the store. Default store writes `target/checkpoint.db` — delete it between runs so a stale row can't fake a pass |
| 29 | `spring-boot-browser-agent` | 9129 | spring-boot | `/atmosphere/ai-chat` | Console | Without `COHERE_API_KEY` + Docker it must **degrade gracefully with guidance and no crash** — that is the out-of-box pass condition |
| 30 | `spring-boot-admin-bundle` | 9130 | spring-boot | `/atmosphere/admin/` | Admin dashboard | Single-dep bundle: Admin Control Plane renders, event stream connects, broadcaster count and runtime are shown |
| 31 | `quarkus-ai-chat` | 18810 | quarkus | `/atmosphere/ai-chat` | Console | Real Ollama **content** through the `atmosphere-quarkus-langchain4j` bridge. Needs explicit config — `LLM_MODE` does not reach LangChain4j here: `--env LLM_BASE_URL=http://localhost:11434/v1 --env LLM_API_KEY=ollama --env LLM_MODEL=qwen2.5:3b` (its properties otherwise default to the Gemini base URL with api-key `dummy`) |
| 32 | `spring-boot-team-rooms` | 9131 | spring-boot | `/atmosphere/console/` | Console | Classic-annotation stack with no AI on the classpath. Point the Console at `/atmosphere/rooms/<name>`; two different room names must show independent membership and history. `GET /api/presence` reports per-room occupancy (from the `@BroadcasterListenerService`) and replay counters (from the `@BroadcasterCacheListenerService`). Posting a bearer-token-shaped string must come back `[redacted]` — that is the `@BroadcasterFilterService` on the wire, not a client-side mask |
| 33 | `spring-boot-low-level-handlers` | 9132 | spring-boot | `/atmosphere/console/` | Console | Two feeds one layer apart: `/atmosphere/raw/ops` (raw `AtmosphereHandler`) and `/atmosphere/managed/ops` (`@ManagedService` twin). `GET /api/health` must show all three listener layers — resource, transport, framework — with `framework.ready` true. `@RoomAuth` is on the raw handler only; it cannot resolve on the managed twin |

## Gating summary — expected PARTIALs

Record these as PARTIAL with the reason named; none is a bug:

| Sample | Gated on | Out-of-box pass condition |
|---|---|---|
| `spring-boot-browser-agent` | `COHERE_API_KEY` + Docker | Graceful degradation message, no crash |
| `spring-boot-coding-agent` | Docker for the sandbox | Interactions timeline verified via `POST /api/interactions`; sandbox verified on the chat path with a GitHub URL in the prompt (`clone https://github.com/octocat/Hello-World.git and read README`) — container evidence must be captured live |
| `spring-boot-channels-chat` | Slack/Telegram/Discord/WhatsApp/Messenger tokens | Web channel verified |
| `spring-boot-dentist-agent` | Slack/Telegram tokens | Web channel verified |
| `spring-boot-otel-chat` | An OTLP collector | Spans produced; export not asserted |
