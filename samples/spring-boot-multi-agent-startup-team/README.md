# Multi-Agent Startup Team — @Coordinator + Fleet

5 AI agents collaborate via the `@Coordinator` fleet abstraction. The CEO coordinator
manages 4 specialist agents, delegating tasks and synthesizing results in real-time.

## The Team

| Agent | Role | Endpoint | How |
|-------|------|----------|-----|
| **CEO** | Coordinates fleet, synthesizes via Gemini | `/atmosphere/agent/ceo` | `@Coordinator` + `@Fleet` + `@Prompt` |
| **Research** | Web scraping via JSoup + DuckDuckGo | `/atmosphere/a2a/research` | `@Agent` + `@AgentSkill` (headless) |
| **Strategy** | SWOT analysis, competitive positioning | `/atmosphere/a2a/strategy` | `@Agent` + `@AgentSkill` (headless) |
| **Finance** | TAM/SAM/SOM, revenue projections | `/atmosphere/a2a/finance` | `@Agent` + `@AgentSkill` (headless) |
| **Writer** | Executive briefing synthesis | `/atmosphere/a2a/writer` | `@Agent` + `@AgentSkill` (headless) |

## How It Works

```
@Coordinator "ceo" with @Fleet of 4 agents
  |
  |-- fleet.agent("research-agent").call("web_search", ...)     (sequential)
  |-- fleet.parallel(                                            (parallel)
  |       fleet.call("strategy-agent", "analyze_strategy", ...),
  |       fleet.call("finance-agent", "financial_model", ...))
  |-- fleet.agent("writer-agent").call("write_report", ...)     (sequential)
  |
  v
session.stream(synthesisPrompt) → Gemini CEO synthesis → streams to browser
```

The `AgentFleet` handles transport automatically: local agents are invoked directly
(no HTTP), remote agents use A2A JSON-RPC. The developer writes only orchestration logic.

## Running

```bash
export GEMINI_API_KEY=your-key
cd samples/spring-boot-multi-agent-startup-team
../../mvnw spring-boot:run
```

Open http://localhost:8080/atmosphere/console/. Demo mode works without a key.

## Key Code: CeoCoordinator

```java
@Coordinator(name = "ceo", skillFile = "prompts/ceo-skill.md")
@Fleet({
    @AgentRef(type = ResearchAgent.class),
    @AgentRef(type = StrategyAgent.class),
    @AgentRef(type = FinanceAgent.class),
    @AgentRef(type = WriterAgent.class)
})
public class CeoCoordinator {

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        var research = fleet.agent("research-agent").call("web_search",
                Map.of("query", message));

        var results = fleet.parallel(
                fleet.call("strategy-agent", "analyze_strategy",
                        Map.of("market", message, "research_findings", research.text())),
                fleet.call("finance-agent", "financial_model",
                        Map.of("market", message)));

        session.stream(synthesize(research, results));
    }
}
```

The specialist agents are plain `@Agent` classes — they don't know they're in a fleet.
