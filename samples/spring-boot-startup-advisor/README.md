# Multi-Agent Startup Team

5 AI agents collaborate in real-time over WebSocket to deliver instant startup advisory briefings.

A user asks a business question. Four specialist agents — Research (web scraping via JSoup), Strategy (market analysis), Finance (TAM/SAM/SOM projections), and Writer (executive briefing) — execute in sequence, each visible as a color-coded card streaming results live. A CEO agent powered by Gemini then synthesizes all findings into a GO/NO-GO recommendation with confidence level, risks, and next steps.

## What Makes It Different

Compared to OpenClaw, CrewAI, or AutoGen:

- **Real-time visibility** — users watch each agent work via WebSocket, not batch output
- **Framework-agnostic** — all Atmosphere AI backends on the classpath (ADK, Spring AI, LangChain4j, built-in)
- **One annotation** — `@Agent` with `@AiTool` methods, zero boilerplate
- **Production architecture** — Spring Boot, MCP protocol auto-registered, conversation memory
- **No external API keys** — only Gemini needed (web scraping uses DuckDuckGo via JSoup)

## The Team

| Agent | Role | Backend | Tool |
|-------|------|---------|------|
| **CEO** | Orchestrates all agents, synthesizes final briefing | Gemini 2.5 Flash | Coordinator |
| **Research Agent** | Scrapes the web for market data, news, competitors | JSoup + DuckDuckGo | `web_search` |
| **Strategy Agent** | SWOT analysis, competitive positioning, go/no-go | Spring AI | `analyze_strategy` |
| **Finance Agent** | TAM/SAM/SOM, revenue projections, funding needs | Built-in Computation | `financial_model` |
| **Writer Agent** | Synthesizes everything into an executive briefing | Google ADK | `write_report` |

## Running

### With Gemini API Key (real multi-agent collaboration)

```bash
export GEMINI_API_KEY=your-gemini-api-key
cd samples/spring-boot-startup-advisor
../../mvnw spring-boot:run
```

### Demo Mode (no API key needed)

```bash
cd samples/spring-boot-startup-advisor
../../mvnw spring-boot:run
```

Open http://localhost:8080 in your browser.

### Frontend Development

```bash
cd frontend
npm install
npm run dev
```

Opens on http://localhost:5173 with proxy to the Spring Boot backend.

## How It Works

```
Browser (React + atmosphere.js)
  |
  | WebSocket
  v
CeoAgent (@Agent, programmatic orchestration)
  |
  |-- AGENT COLLABORATION ──────────────────
  |   |-- web_search      -> JSoup scraping (DuckDuckGo)
  |   |-- analyze_strategy -> SWOT framework + LLM
  |   |-- financial_model  -> TAM/SAM/SOM computation
  |   |-- write_report     -> Executive briefing template
  |
  |-- CEO SYNTHESIS ─────────────────────────
  |   |-- Gemini 2.5 Flash synthesizes all agent findings
  |   |-- Streams GO/NO-GO recommendation in real-time
  |
  v
Frontend renders agent cards + CEO briefing via WebSocket
```

## Example Prompts

- "Analyze the market for AI-powered developer tools in 2026"
- "What's the opportunity in AI agents for enterprise?"
- "Should we build a competitor to Cursor?"
- "Evaluate the market for real-time collaboration tools"

## Key Files

| File | Description |
|------|-------------|
| `CeoAgent.java` | Main agent with 4 specialist tool methods |
| `LlmConfig.java` | ADK + Gemini configuration with tool registration |
| `DemoResponseProducer.java` | Demo mode fallback |
| `prompts/ceo-skill.md` | System prompt for the CEO |
| `frontend/src/App.tsx` | React UI with agent cards and status bar |
