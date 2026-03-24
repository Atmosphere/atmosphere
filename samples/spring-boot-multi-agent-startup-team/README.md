# A2A Startup Team — True Multi-Agent

5 independent AI agents collaborate via the A2A (Agent-to-Agent) protocol over JSON-RPC.

Each specialist is a headless `@Agent` with `@Skill` methods — headless mode is auto-detected when there's no `@Prompt`. The CEO discovers them via Agent Cards and delegates via HTTP JSON-RPC.

## The Team

| Agent | Role | Endpoint | How |
|-------|------|----------|-----|
| **CEO** | Orchestrates, synthesizes via Gemini | `/atmosphere/agent/ceo` | `@Agent` + `@Prompt` (full-stack) |
| **Research** | Web scraping via JSoup + DuckDuckGo | `/atmosphere/a2a/research` | `@Agent` + `@Skill` (headless) |
| **Strategy** | SWOT analysis, competitive positioning | `/atmosphere/a2a/strategy` | `@Agent` + `@Skill` (headless) |
| **Finance** | TAM/SAM/SOM, revenue projections | `/atmosphere/a2a/finance` | `@Agent` + `@Skill` (headless) |
| **Writer** | Executive briefing synthesis | `/atmosphere/a2a/writer` | `@Agent` + `@Skill` (headless) |

## How It Works

```
CEO @Agent (WebSocket UI)
  |
  |-- GET  /atmosphere/a2a/research  → discovers Agent Card
  |-- POST /atmosphere/a2a/research  → message/send { skillId: "web_search" }
  |-- POST /atmosphere/a2a/strategy  → message/send { skillId: "analyze_strategy" }
  |-- POST /atmosphere/a2a/finance   → message/send { skillId: "financial_model" }
  |-- POST /atmosphere/a2a/writer    → message/send { skillId: "write_report" }
  |
  v
Gemini synthesizes all agent findings → streams to browser
```

## Running

```bash
export GEMINI_API_KEY=your-key
cd samples/spring-boot-multi-agent-startup-team
../../mvnw spring-boot:run
```

Open http://localhost:8080. Demo mode works without a key.

## Key Difference: Unified `@Agent`

All 5 agents use ONE annotation. Headless mode is auto-detected:

```java
// Full-stack (has @Prompt → WebSocket UI)
@Agent(name = "ceo", skillFile = "ceo.md")
public class CeoAgent {
    @Prompt
    public void onMessage(String msg, StreamingSession s) { ... }
}

// Headless (has @Skill, no @Prompt → A2A only)
@Agent(name = "research", endpoint = "/atmosphere/a2a/research")
public class ResearchAgent {
    @Skill(id = "web_search", name = "Search", ...)
    @SkillHandler
    public void search(TaskContext task, @SkillParam(name="query") String q) { ... }
}
```

No `@Agent` annotation — it was removed in favor of the unified `@Agent`.
