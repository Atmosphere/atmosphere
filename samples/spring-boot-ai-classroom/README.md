# AI Classroom

**Real-time collaborative AI streaming** — multiple clients in the same room see the AI response stream text-by-text, simultaneously.

This sample demonstrates what makes Atmosphere unique: **broadcasting streamed LLM texts to multiple connected clients** using a single `@AiEndpoint` annotation.

## Architecture

```
┌──────────┐   ┌──────────┐   ┌──────────┐
│ Student A│   │ Student B│   │ Student C│
└────┬─────┘   └────┬─────┘   └────┬─────┘
     │              │              │
     │  WebSocket   │  WebSocket   │  WebSocket
     │              │              │
     └──────────────┼──────────────┘
                    │
           ┌────────▼────────┐
           │   Atmosphere    │
           │   Broadcaster   │  ← All connected clients share this
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  AiClassroom    │  @AiEndpoint + @Prompt
           │  + Interceptor  │  RoomContextInterceptor sets persona
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  AgentRuntime   │  Pluggable backend (built-in, Spring AI,
           │  (auto-detect)  │  LangChain4j, ADK, Embabel, Koog — zero code change)
           └─────────────────┘
```

## Key Code

**The endpoint (6 lines of meaningful code):**

```java
@AiEndpoint(path = "/atmosphere/classroom/{room}",
        systemPromptResource = "skill:classroom",
        interceptors = { RoomContextInterceptor.class })
public class AiClassroom {

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        session.stream(message);  // Works with ANY AgentRuntime backend
    }
}
```

The `{room}` path segment is extracted by `AiEndpointHandler` and each unique room path gets its own Atmosphere broadcaster, so messages in the math room are isolated from the code and science rooms. The `skill:classroom` prefix loads the system prompt from a skill file (classpath or `~/.atmosphere/skills/`).

## Governance — YAML-driven per-room scope (v4 Goal 1)

**This sample is unique in the JVM AI space**: ONE `@AiEndpoint` serves
four DIFFERENT scopes selected per-request from the `{room}` path param.
No other framework (MS Agent Framework, Spring AI, LangChain4j) has
per-request scope install — they'd need four separate endpoints for this.

### How the per-room scope works

1. `atmosphere-classroom-scopes.yaml` declares four rooms. Each room has its
   own system prompt, purpose, forbidden topics, redirect message, and scope
   tier. Edit YAML, restart, rooms change — zero Java edits.
2. `RoomScopesConfig` loads the YAML at boot, publishes a
   `Rooms` bean, and hands the registry to `RoomContextInterceptor` via a
   static installer.
3. On every incoming request, `RoomContextInterceptor.preProcess` reads the
   `{room}` path param, picks the matching `ScopeConfig`, and places it on
   `AiRequest.metadata()` under `ScopePolicy.REQUEST_SCOPE_METADATA_KEY`.
4. `AiStreamingSession.stream` pops that key, builds a transient
   `ScopePolicy` for this request, runs pre-admission + system-prompt
   hardening, and wraps the streamed response with a matching post-response
   check.

The math room rejects "write me python code"; the code room rejects
"medical advice"; the science room rejects both. One endpoint, four
scopes, all swappable from YAML.

```yaml
# atmosphere-classroom-scopes.yaml (excerpt)
rooms:
  math:
    purpose: Mathematics tutoring — algebra, calculus, geometry, statistics
    forbiddenTopics:
      - writing source code
    redirectMessage: "This is the math room — ask me about a mathematics topic."
    tier: RULE_BASED
  code:
    purpose: Software engineering mentoring — languages, debugging, algorithms
    forbiddenTopics:
      - medical advice
      - legal advice
    redirectMessage: "This is the code room — ask me about a programming topic."
    tier: RULE_BASED
```

### Additional framework-level governance (separate from per-room scope)

`atmosphere-policies.yaml` (loaded by `PoliciesConfig`) layers cross-cutting
policies that apply to all rooms: `classroom-pii-guard` (PII redaction) and
`classroom-drift-watcher` (response-length z-score). Built-in YAML policy
types: `pii-redaction`, `cost-ceiling`, `output-length-zscore`, `deny-list`,
`allow-list`, `message-length`, `rate-limit`, `concurrency-limit`,
`time-window`, `metadata-presence`, `authorization`.

## Running

The easiest way to run with a real AI model is via [Embacle](https://github.com/dravr-ai/dravr-embacle), which turns your existing Claude Code, Copilot, Cursor, or Gemini CLI license into an OpenAI-compatible LLM provider — no separate API key required.

### With Embacle (recommended)

```bash
# 1. Start Embacle (see https://github.com/dravr-ai/dravr-embacle)
#    It runs on http://localhost:3000/v1

# 2. Start the classroom with Embacle as the backend
LLM_BASE_URL=http://localhost:3000/v1 LLM_API_KEY=embacle LLM_MODEL=copilot:claude-sonnet-4.6 \
  ./mvnw spring-boot:run -pl samples/spring-boot-ai-classroom

# Open http://localhost:8080 in MULTIPLE browser tabs
# Join the same room, send a question — all tabs stream simultaneously
```

### With other providers

```bash
# Gemini
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash

# OpenAI
export LLM_API_KEY=sk-...
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1

# Local Ollama
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

Without any API key or Embacle, the sample runs in **demo mode** with simulated streaming responses.

## Rooms

Each room is a path segment — connect to a different URL to join a different room. Each path also gets its own Atmosphere broadcaster so messages stay isolated per room.

| Room | Persona | URL |
|------|---------|-----|
| Math | Mathematics tutor | `/atmosphere/classroom/math` |
| Code | Programming mentor | `/atmosphere/classroom/code` |
| Science | Science educator | `/atmosphere/classroom/science` |
| General | General assistant | `/atmosphere/classroom/general` |

## Portability

The `session.stream(message)` call is **framework-agnostic**. To switch AI backends:

| Backend | What to do |
|---------|-----------|
| Built-in (OpenAI-compatible) | Default — just set `LLM_API_KEY` |
| Spring AI | Add `atmosphere-spring-ai` dependency |
| LangChain4j | Add `atmosphere-langchain4j` dependency |
| Google ADK | Add `atmosphere-adk` dependency |
| Embabel | Add `atmosphere-embabel` dependency |
| JetBrains Koog | Add `atmosphere-koog` dependency |

**Zero code changes.** The `AgentRuntime` SPI auto-detects the best available backend via `ServiceLoader`.

## Mobile Client

A React Native / Expo client is available at [expo-client](expo-client/). It connects to this backend via WebSocket, streams AI responses text-by-text with markdown rendering, and includes AppState/NetInfo lifecycle integration. See the [React Native client docs](https://atmosphere.github.io/docs/clients/react-native/) for details.
