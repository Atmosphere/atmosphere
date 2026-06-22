# Spring Boot AG-UI Chat Sample

A **real** [AG-UI (Agent-User Interaction)](https://docs.ag-ui.com/) agent. A
user message drives a real Atmosphere `AgentRuntime` whose `AiEvent`s are mapped
to AG-UI events by the shipped `AgUiEventMapper` and streamed to the browser as
Server-Sent Events — compatible with CopilotKit and similar frontends.

The agent is a plain `@Agent` class. Because `atmosphere-agui` is on the
classpath, the framework auto-registers an AG-UI SSE endpoint alongside it — no
hand-rolled controller. When no LLM key is configured the agent falls back to a
deterministic `DemoResponseProducer` so the sample streams a real AG-UI event
sequence out of the box (same no-key contract as
[`spring-boot-ai-chat`](../spring-boot-ai-chat/) and
[`spring-boot-ai-tools`](../spring-boot-ai-tools/)).

## What It Does

When you send a message, the agent:

1. Emits `RUN_STARTED`.
2. **With an LLM key:** runs the real `AiPipeline` — the model streams text
   (`TEXT_MESSAGE_START` → `TEXT_MESSAGE_CONTENT` → `TEXT_MESSAGE_END`) and may
   dispatch the real `@AiTool` methods `get_weather` / `get_time`, surfaced as
   `TOOL_CALL_START` → `TOOL_CALL_ARGS` → `TOOL_CALL_RESULT` → `TOOL_CALL_END`.
3. **Without a key (demo mode):** streams a deterministic reply word-by-word as
   `TEXT_MESSAGE_CONTENT` frames. The demo path does **not** call the model, so
   no tool dispatch happens — that is exclusively the keyed path.
4. Emits `RUN_FINISHED`.

The `@AiTool` bodies (`get_weather`, `get_time`) return illustrative,
deterministic data — this sample demonstrates real tool-calling **wiring**, not a
live weather/time service. They are genuine `@AiTool` methods the runtime
dispatches (not scripted `if (message.contains("weather"))` branches); swap the
bodies for a real API call to make them production-grade.

## Running

```bash
# Build the frontend first (builds atmosphere.js, then the React app into static/)
cd ../../atmosphere.js && npm install && npm run build && cd -
cd samples/spring-boot-agui-chat/frontend && npm install && npx vite build && cd ../../..

# Demo mode (no key) — works out of the box
./mvnw spring-boot:run -pl samples/spring-boot-agui-chat

# Real model — set a key (Gemini / OpenAI / any OpenAI-compatible provider)
LLM_API_KEY=sk-... ./mvnw spring-boot:run -pl samples/spring-boot-agui-chat
```

Open **http://localhost:8085** — the bespoke AG-UI React UI is served at `/`.
(The generic Atmosphere console remains at `/atmosphere/console/`.)

| Message | Demo mode | With LLM key |
|---------|-----------|--------------|
| `Hello!` | Streamed greeting | Real model reply |
| `What's the weather in Paris?` | Streamed text (no tool call) | `get_weather` tool dispatched + streamed answer |
| `What time is it in Tokyo?` | Streamed text (no tool call) | `get_time` tool dispatched + streamed answer |

## The Real Wire Path

```
Browser (React + atmosphere.js/chat)
   │  POST /atmosphere/agent/assistant/agui   (AG-UI RunContext JSON)
   ▼
AgUiHandler                       parse RunContext, emit RUN_STARTED, run on a virtual thread
   │  invokes
   ▼
AssistantAgent.onPrompt(msg, session)
   │  keyed:  session.stream(msg)             demo: DemoResponseProducer.stream(msg, session)
   ▼
AiPipeline.execute(threadId, msg, session)    LLM + @AiTool dispatch (keyed path)
   │  emits AiEvent.TextDelta / ToolStart / ToolResult / TextComplete / ...
   ▼
ResourceAgUiStreamingSession.emit(AiEvent)
   │  AgUiEventMapper.toAgUi(event)
   ▼
SSE frames flushed to the browser             event: TEXT_MESSAGE_CONTENT\ndata: {...}\n\n
   │  on completion
   ▼
RUN_FINISHED
```

- **Endpoint:** `POST /atmosphere/agent/assistant/agui` (auto-registered by
  `AgentProcessor.registerAgUi` because `atmosphere-agui` is on the classpath;
  the handler is wired with the agent's real `AiPipeline`).
- **WS UI handler:** the same `@Agent` also exposes `/atmosphere/agent/assistant`
  (Atmosphere's WebSocket chat surface); this sample's bespoke UI uses the AG-UI
  SSE endpoint, not the WS one.

## Demo vs. Real-Key Contract

`AssistantAgent.onPrompt` reads `AiConfig.get()`:

- `apiKey()` present → `session.stream(message)` drives the real pipeline.
- `apiKey()` null/blank → `DemoResponseProducer.stream(message, session)`.

Both paths emit identical AG-UI lifecycle frames (`RUN_STARTED` … text frames …
`RUN_FINISHED`) through the same `StreamingSession`, so the frontend behaves the
same; only the *content* and *tool dispatch* differ.

## Key Code

| File | Purpose |
|------|---------|
| `AssistantAgent.java` | `@Agent` with `@Prompt(String, StreamingSession)` + real `@AiTool get_weather` / `get_time` |
| `DemoResponseProducer.java` | No-key fallback — streams real AG-UI frames via the session |
| `LlmConfig.java` | Resolves `AiConfig` from `llm.*` properties (`LLM_API_KEY`, `llm.model`, …) |
| `AgUiChatApplication.java` | Serves the bespoke AG-UI React UI at `/` |
| `frontend/src/App.tsx` | React app: `fetch()` + `ReadableStream` SSE parser, posts to the AG-UI endpoint |

The AG-UI mapping and SSE bridge are shipped framework code — see
`modules/agui` (`AgUiEventMapper`, `AgUiHandler`) — not part of this sample.

## See Also

- [spring-boot-ai-chat](../spring-boot-ai-chat/) — Atmosphere-native AI streaming over WebSocket
- [spring-boot-ai-tools](../spring-boot-ai-tools/) — `@AiTool` dispatch across runtimes
- [spring-boot-a2a-agent](../spring-boot-a2a-agent/) — A2A protocol (agent ↔ agent)
- [spring-boot-mcp-server](../spring-boot-mcp-server/) — MCP protocol (agent ↔ tools)
