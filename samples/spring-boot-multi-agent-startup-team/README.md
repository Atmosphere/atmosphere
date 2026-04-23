# Multi-Agent Startup Team

5 AI agents collaborate in real-time via the `@Coordinator` fleet abstraction.
A CEO coordinator manages 4 specialist agents, delegating tasks via the A2A
protocol and synthesizing results into an executive briefing streamed to the
browser over WebTransport/HTTP3.

This sample demonstrates:
- **`@Coordinator`** + **`@Fleet`** for multi-agent orchestration
- **`AgentFleet`** API for sequential and parallel agent dispatch
- **`@Agent`** + **`@AgentSkill`** for headless specialist agents
- **Agent Activity Streaming** — real-time `agent-step` events (thinking/completed) streamed to the browser via `StreamingActivityListener`
- **Coordination Journal** with rendered markdown tables showing the full execution graph
- **Governance policy plane (all 4 v4 goals)** — `@AgentScope` on the coordinator, `PolicyAdmissionGate.admit` at user input, `GovernanceFleetInterceptor` at every cross-agent dispatch, signed `CommitmentRecord`s on the journal. See [§ Governance](#governance--what-you-can-do-at-runtime).
- **Result Evaluation** — dual evaluators (`SanityCheckEvaluator` + `LlmResultEvaluator`) auto-score agent responses with EVAL rows in the journal
- **SQLite Checkpoints** — `CheckpointingCoordinationJournal` persists coordination state to `atmosphere-checkpoints.db`
- **Skill files from GitHub** — `skill:` prefix loads prompts from [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) with SHA-256 integrity verification
- **WebTransport over HTTP/3** — self-signed cert auto-discovery via `/api/webtransport-info`
- **Admin Control Plane** — live dashboard at `/atmosphere/admin/` with kill-switch, hot-reload, OWASP matrix, `agt verify` export
- **4 swappable AI runtimes**: ADK (default), Embabel, Spring AI, LangChain4j

## Prerequisites

- Java 21+
- A Gemini API key (set `GEMINI_API_KEY` env var). Demo mode works without one.

## Quick Start

```bash
export GEMINI_API_KEY=your-key-here
./mvnw spring-boot:run -pl samples/spring-boot-multi-agent-startup-team
```

Open [http://localhost:8080/atmosphere/console](http://localhost:8080/atmosphere/console)
and type a prompt like: *"Analyze the market for AI developer tools"*

Open [http://localhost:8080/atmosphere/admin/](http://localhost:8080/atmosphere/admin/)
to see the admin dashboard with live event stream, all 5 agents, fleet topology, and operational controls.

## The Team

| Agent | Role | Skill | Transport |
|-------|------|-------|-----------|
| **CEO** | Coordinates fleet, synthesizes briefing | `@Prompt` + `AgentFleet` | WebSocket |
| **Research** | Web scraping via JSoup + DuckDuckGo | `web_search` | A2A (local) |
| **Strategy** | SWOT analysis, competitive positioning | `analyze_strategy` | A2A (local) |
| **Finance** | TAM/SAM/SOM, revenue projections | `financial_model` | A2A (local) |
| **Writer** | Executive briefing synthesis | `write_report` | A2A (local) |

## How It Works

```
User message (WebSocket)
  |
  v
@Coordinator "ceo" with @Fleet of 4 agents
  |
  |-- Step 1: fleet.agent("research-agent").call("web_search")       [sequential]
  |
  |-- Step 2: fleet.parallel(                                         [parallel]
  |       fleet.call("strategy-agent", "analyze_strategy"),
  |       fleet.call("finance-agent", "financial_model"))
  |
  |-- Step 3: fleet.agent("writer-agent").call("write_report")       [sequential]
  |
  |-- Step 4: fleet.journal().formatLog()                            [observability]
  |
  v
session.stream(synthesisPrompt) --> Gemini LLM --> streams to browser
```

The `AgentFleet` handles transport automatically: local agents are invoked
directly (no HTTP), remote agents use A2A JSON-RPC over HTTP. The developer
writes only orchestration logic.

## Project Structure

```
src/main/java/.../a2astartup/
  A2aStartupTeamApplication.java   # Spring Boot entry point
  CeoCoordinator.java              # @Coordinator with @Fleet (the orchestrator)
  ResearchAgent.java               # @Agent: web search via JSoup
  StrategyAgent.java               # @Agent: SWOT analysis
  FinanceAgent.java                # @Agent: financial modeling
  WriterAgent.java                 # @Agent: report synthesis
  CheckpointConfig.java            # SQLite-backed CoordinationJournal via ServiceLoader
  DemoResponseProducer.java        # Fallback when no API key
  LlmConfig.java                   # LLM settings from env vars

src/main/resources/
  application.yml                  # Atmosphere + LLM config
  META-INF/services/
    ...ResultEvaluator             # SanityCheckEvaluator + LlmResultEvaluator
    ...CoordinationJournal         # CheckpointConfig (SQLite-backed journal)
```

Skill files are loaded from [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills)
at runtime via `skill:startup-ceo`, `skill:startup-research`, etc. No local prompt
files — the `PromptLoader` fetches from GitHub on first run and caches to `~/.atmosphere/skills/`.

## Key Code

### The Coordinator (CeoCoordinator.java)

The `@Coordinator` annotation registers this class as a fleet manager.
`@Fleet` declares which agents belong to the fleet. The `AgentFleet` is
injected into the `@Prompt` method automatically. The `skill:` prefix
loads the CEO persona from the `atmosphere-skills` GitHub repo.

```java
@Coordinator(name = "ceo",
    skillFile = "skill:startup-ceo",
    description = "Startup CEO that coordinates specialist A2A agents")
@Fleet({
    @AgentRef(type = ResearchAgent.class),
    @AgentRef(type = StrategyAgent.class),
    @AgentRef(type = FinanceAgent.class),
    @AgentRef(type = WriterAgent.class)
})
public class CeoCoordinator {

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        // Wire per-session activity streaming — clients see agent-step events in real time
        fleet = fleet.withActivityListener(new StreamingActivityListener(session));

        // Step 1: Research (sequential)
        var research = fleet.agent("research-agent").call("web_search",
                Map.of("query", message, "num_results", "3"));

        // Step 2: Strategy + Finance (parallel)
        var results = fleet.parallel(
                fleet.call("strategy-agent", "analyze_strategy",
                        Map.of("market", message, "research_findings", research.text())),
                fleet.call("finance-agent", "financial_model",
                        Map.of("market", message)));

        // Step 3: Writer synthesis
        var report = fleet.agent("writer-agent").call("write_report",
                Map.of("title", message, "key_findings", research.text()));

        // Step 4: CEO LLM synthesis
        session.stream("Write an executive briefing based on: " + report.text());
    }
}
```

The `StreamingActivityListener` emits `agent-step` events to the WebSocket as each
agent transitions through Thinking -> Completed states. The `JournalingAgentFleet`
(wired via `CheckpointConfig`) auto-evaluates each result with both
`SanityCheckEvaluator` (word count, structure) and `LlmResultEvaluator` (Gemini
judge via `AgentRuntime.generate()`). EVAL rows appear in the coordination journal
with evaluator name, score, reason, and PASS/FAIL status.

### A Specialist Agent (ResearchAgent.java)

Specialist agents are plain `@Agent` classes with `@AgentSkill` methods.
They don't know they're in a fleet — the coordinator dispatches to them
via the A2A protocol.

```java
@Agent(name = "research-agent",
    skillFile = "prompts/research-skill.md",
    description = "Web research agent",
    endpoint = "/atmosphere/a2a/research")
public class ResearchAgent {

    @AgentSkill(id = "web_search", name = "Web Search",
        description = "Search the web for market data and news")
    @AgentSkillHandler
    public void webSearch(TaskContext task,
            @AgentSkillParam(name = "query") String query,
            @AgentSkillParam(name = "num_results") String numResults) {
        task.updateStatus(TaskState.WORKING, "Searching: " + query);
        // ... JSoup scraping logic ...
        task.addArtifact(Artifact.text(results));
        task.complete("Found " + count + " results");
    }
}
```

### Skill Files (atmosphere-skills repo)

Agent personas are loaded from the [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills)
GitHub repo via the `skill:` prefix. On first run, `PromptLoader.loadSkill()` fetches each
skill file from GitHub, verifies its SHA-256 hash against `registry.json` (supply chain
protection), and caches it to `~/.atmosphere/skills/`.

```java
@Agent(name = "research-agent",
    skillFile = "skill:startup-research",  // loaded from GitHub
    endpoint = "/atmosphere/a2a/research")
```

To use a local skill file instead, drop the `skill:` prefix:
```java
@Agent(name = "custom", skillFile = "prompts/custom.md")  // classpath only
```

The search order: classpath -> disk cache (`~/.atmosphere/skills/`) -> GitHub raw.
Set `atmosphere.skills.offline=true` for air-gapped environments.

## Switching AI Runtimes

The sample supports 4 AI runtimes. Only one is active at a time — swap by
editing the dependencies in `pom.xml`:

| Runtime | Default | Profile | Notes |
|---------|---------|---------|-------|
| **Google ADK** | Yes | — | `atmosphere-adk` + `google-adk` |
| **Embabel** | No | `-Pspring-boot3` | `atmosphere-embabel` + Embabel starters (requires SB 3.5) |
| **Spring AI** | No | — | `atmosphere-spring-ai` + `spring-ai-openai` |
| **LangChain4j** | No | — | `atmosphere-langchain4j` + `langchain4j-open-ai` |

To switch to Embabel:
1. Comment out ADK dependencies in `pom.xml`
2. Uncomment Embabel dependencies
3. Run with: `./mvnw spring-boot:run -pl samples/spring-boot-multi-agent-startup-team -Pspring-boot3`

The console will show `Runtime: embabel` (or whichever runtime is active).

## Coordination Journal + Result Evaluation

The `CoordinationJournal` records every dispatch, completion, evaluation, and
routing decision. It renders as a markdown table in the browser via `remark-gfm`:

| Event | Agent | Detail | Duration |
|-------|-------|--------|----------|
| DISPATCH | research-agent | web_search | -- |
| DONE | research-agent | web_search | 336ms |
| START | -- | ceo | -- |
| DISPATCH | strategy-agent | analyze_strategy | -- |
| DISPATCH | finance-agent | financial_model | -- |
| DONE | strategy-agent | analyze_strategy | 3ms |
| DONE | finance-agent | financial_model | 3ms |
| COMPLETE | -- | 2 calls | 6ms |
| EVAL | research-agent | [sanity-check] 1.0 -- 64 words, structured | PASS |
| EVAL | research-agent | [llm-judge] 0.8 -- comprehensive research | PASS |

Two evaluators run automatically via ServiceLoader:

- **`SanityCheckEvaluator`** — hardcoded baseline (word count, error keywords, structure).
  No API key needed. Works in CI.
- **`LlmResultEvaluator`** — calls the active `AgentRuntime` (Gemini, etc.) as an
  LLM-as-judge. Prompt loaded from `atmosphere-skills/llm-judge/SKILL.md`.
  Uses `AgentRuntime.generate()` for synchronous one-shot evaluation.

Evaluations run asynchronously on a serialized virtual thread executor to avoid
rate-limiting LLM APIs. Results stream to the client in real time via
`AgentActivity.Evaluated` -> `StreamingActivityListener` -> `AiEvent.AgentStep("eval", ...)`.

## SQLite Checkpoints

The `CheckpointConfig` class wires `CheckpointingCoordinationJournal` with
`SqliteCheckpointStore`. Coordination state persists to `atmosphere-checkpoints.db`
and survives JVM restarts.

```bash
# After a request, verify checkpoints on disk:
sqlite3 atmosphere-checkpoints.db "SELECT COUNT(*) FROM checkpoints;"
```

## Governance — what you can do at runtime

This sample applies all four v4 governance goals. `GovernanceConfig` publishes
a policy chain at boot; `CeoCoordinator` evaluates it at `@Prompt` entry AND
on every cross-agent dispatch.

### Goals applied

| Goal | What this sample does | Where to look |
|---|---|---|
| **1 — MS YAML acceptance** | `GovernanceConfig.policyPlanePublisher()` publishes 4 admission policies on `GovernancePolicy.POLICIES_PROPERTY`; the framework evaluates them at admission. Drop `atmosphere-policies.yaml` (MS or native schema) on the classpath and it loads alongside. | [GovernanceConfig.java](src/main/java/org/atmosphere/samples/springboot/a2astartup/GovernanceConfig.java) |
| **2 — Architectural scope enforcement** | `@AgentScope` on `CeoCoordinator` declares the startup-advisory purpose + forbidden topics. `PolicyAdmissionGate.admit` runs at `@Prompt` entry. `GovernanceFleetInterceptor` gates every coord→specialist dispatch. | [CeoCoordinator.java](src/main/java/org/atmosphere/samples/springboot/a2astartup/CeoCoordinator.java) |
| **3 — Commitment records** | `Ed25519CommitmentSigner` bean + `CommitmentRecordsFlag.override(true)` in `GovernanceConfig` — every dispatch emits a VC-subtype signed record on the coordination journal. Visible in the admin **Commitments** tab. | `GovernanceConfig.commitmentSigner()` |
| **4 — OWASP + compliance evidence** | All evidence rows point at primitives this sample exercises (`PolicyAdmissionGate`, `@AgentScope`, `ScopePolicy`). CI gate (`EvidenceConsumerGrepPinTest`) keeps the claims honest. | `/api/admin/governance/agt-verify` |

### Exercise the goals live

```bash
# Who's enforcing right now?
curl http://localhost:8080/api/admin/governance/policies
# Returns 4 policies with sha256 digests + armed/timed/dry-run flags

# Full health snapshot
curl http://localhost:8080/api/admin/governance/health

# Send an off-topic prompt through the MS-compatible /check endpoint
curl -X POST http://localhost:8080/api/admin/governance/check \
     -H 'Content-Type: application/json' \
     -d '{"agent_id":"ceo","action":"prompt","context":{"message":"write_code in python"}}'
# → {"allowed":false,"matched_policy":"dispatch-deny","evaluation_ms":1.14}

# Break-glass — arm the kill switch
curl -X POST http://localhost:8080/api/admin/governance/kill-switch/arm \
     -H 'Content-Type: application/json' \
     -d '{"reason":"incident-42","operator":"oncall"}'

# Compliance export (cross-vendor agt verify schema)
curl http://localhost:8080/api/admin/governance/agt-verify | jq '.summary'
# → OWASP 9/1 covered/not-addressed, EU_AI_ACT 4/1, HIPAA 3/1/1, SOC2 3/2
```

Every decision streams into the admin **Decisions** tab — expand an entry to
see the matched policy, reason, and redaction-safe context snapshot.

### Why this matters

The combination below isn't possible in any other JVM AI framework:

- **Streaming transport + governance**: decisions flow through the same
  WebSocket/SSE the UI uses. Admin console sees policy denies as they happen.
- **Per-dispatch enforcement**: `GovernanceFleetInterceptor` gates every
  coord→specialist hop — a coordinator mistakenly dispatching "write Python"
  to the research agent gets denied at the fleet boundary, not just at the
  user-facing entry.
- **Signed audit trail over the same transport**: the admin Commitments tab
  renders Ed25519-signed `CommitmentRecord`s as they land on the journal.

## Admin Dashboard

The `atmosphere-admin` dependency enables a real-time management dashboard at `/atmosphere/admin/`:

- **Dashboard** — live counters (5 agents, 12 broadcasters), WebSocket event feed, connected resources
- **Agents** — all 5 agents listed: CEO coordinator (v1.0.0) + 4 headless specialists with a2a/mcp protocol badges
- **Journal** — query coordination events by coordination ID or agent name
- **Control** — broadcast messages to any agent, disconnect clients, cancel A2A tasks, with full audit trail

The event stream uses Atmosphere's own WebSocket transport — the admin dashboard eats its own dog food.

```bash
# REST API
curl http://localhost:8080/api/admin/overview     # system snapshot
curl http://localhost:8080/api/admin/agents        # all 5 agents
curl http://localhost:8080/api/admin/broadcasters   # 12 active broadcasters
```

## WebTransport over HTTP/3

The sample starts a Reactor Netty HTTP/3 sidecar on port 4446 with a self-signed
ECDSA certificate. The frontend auto-discovers the transport via
`/api/webtransport-info` (returns port + SHA-256 certificate hash) and connects
with `serverCertificateHashes`. Falls back to WebSocket if WebTransport is unavailable.

## Console Output

When you send a prompt, the console shows:
1. **Agent Activity Events** — `agent-step` frames stream in real time: "Agent 'research-agent' is thinking...", "completed in 336ms"
2. **AGENT COLLABORATION** — tool cards for each agent with expandable results (rendered as markdown via `remark-gfm`)
3. **Coordination Journal** — full execution table with EVAL rows showing evaluator name, score, reason, and PASS/FAIL
4. **CEO Briefing** — the LLM-generated executive summary with GO/NO-GO recommendation, key risks, and next steps
5. **Agent Status Bar** — bottom bar with per-agent status (thinking/completed) and green checkmarks
