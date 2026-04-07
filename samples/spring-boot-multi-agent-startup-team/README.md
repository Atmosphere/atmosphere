# Multi-Agent Startup Team

5 AI agents collaborate in real-time via the `@Coordinator` fleet abstraction.
A CEO coordinator manages 4 specialist agents, delegating tasks via the A2A
protocol and synthesizing results into an executive briefing streamed to the
browser over WebSocket.

This sample demonstrates:
- **`@Coordinator`** + **`@Fleet`** for multi-agent orchestration
- **`AgentFleet`** API for sequential and parallel agent dispatch
- **`@Agent`** + **`@AgentSkill`** for headless specialist agents
- **Coordination Journal** for execution observability
- **Admin Control Plane** — live dashboard at `/atmosphere/admin/` with event stream, agent inspection, and operational controls
- **4 swappable AI runtimes**: ADK (default), Embabel, Spring AI, LangChain4j
- **Skill files** (`prompts/*.md`) defining agent personas and capabilities

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
  DemoResponseProducer.java        # Fallback when no API key
  LlmConfig.java                   # LLM settings from env vars

src/main/resources/
  application.yml                  # Atmosphere + LLM config
  prompts/
    ceo-skill.md                   # CEO persona and guardrails
    research-skill.md              # Research agent skill definition
    strategy-skill.md              # Strategy agent skill definition
    finance-skill.md               # Finance agent skill definition
    writer-skill.md                # Writer agent skill definition
```

## Key Code

### The Coordinator (CeoCoordinator.java)

The `@Coordinator` annotation registers this class as a fleet manager.
`@Fleet` declares which agents belong to the fleet. The `AgentFleet` is
injected into the `@Prompt` method automatically.

```java
@Coordinator(name = "ceo",
    skillFile = "prompts/ceo-skill.md",
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

        // Step 4: Coordination Journal (observability)
        session.emit(new AiEvent.ToolResult("coordination_journal",
                fleet.journal().formatLog(JournalFormat.MARKDOWN)));

        // Step 5: CEO LLM synthesis
        session.stream("Write an executive briefing based on: " + report.text());
    }
}
```

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

### Skill Files (prompts/*.md)

Each agent has a skill file that defines its persona, capabilities, and
guardrails. The file becomes the agent's system prompt.

```markdown
# Research Agent

You are a market research specialist. You search the web for market data,
competitor intelligence, and industry reports.

## Skills
- Search the web for market data, news, and competitor information
- Extract and summarize relevant findings from search results

## Guardrails
- Always cite sources with URLs
- Clearly label cached/fallback data as such
- Do not fabricate statistics
```

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

## Coordination Journal

The `CoordinationJournal` records every dispatch, completion, and failure
in the fleet execution. After all agents complete, the CEO queries the
journal and displays it as a tool card in the console:

```
10 events:
  DISPATCH  research-agent -> web_search
  DONE      research-agent (732ms)
  START     ceo
  DISPATCH  strategy-agent -> analyze_strategy
  DISPATCH  finance-agent -> financial_model
  DONE      strategy-agent (3ms)
  DONE      finance-agent (3ms)
  COMPLETE  2 calls in 5ms
  DISPATCH  writer-agent -> write_report
  DONE      writer-agent (1ms)
```

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

## Console Output

When you send a prompt, the console shows:
1. **AGENT COLLABORATION** — tool cards for each agent (Web Search, Analyze Strategy, Financial Model, Write Report)
2. **Coordination Journal** — the full execution trace
3. **CEO Briefing** — the LLM-generated executive summary with GO/NO-GO recommendation, key risks, and next steps
