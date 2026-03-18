# Spring Boot AG-UI Chat Sample

A streaming AI assistant that speaks the [AG-UI (Agent-User Interaction)](https://docs.ag-ui.com/) protocol. The agent streams steps, tool calls, and text to the browser via Server-Sent Events — compatible with CopilotKit and similar frontends.

## What It Does

When you send a message, the assistant:

1. Emits **agent steps** (`STEP_STARTED` / `STEP_FINISHED`) — "Analyzing", "Generating response"
2. Optionally executes **tool calls** (`TOOL_CALL_START` / `TOOL_CALL_RESULT`) — weather lookups, time queries
3. Streams **text word by word** (`TEXT_MESSAGE_CONTENT`) with a blinking cursor
4. Finishes with `RUN_FINISHED`

All 28 AG-UI event types are supported (lifecycle, text, tools, state, reasoning, activity).

## Running

```bash
# Build the frontend first
cd samples/spring-boot-agui-chat/frontend && npm install && npx vite build && cd ../../..

# Start the server
./mvnw spring-boot:run -pl samples/spring-boot-agui-chat
```

Open **http://localhost:8085** and try these messages:

| Message | What happens |
|---------|-------------|
| `Hello!` | Steps + streamed greeting |
| `What's the weather?` | Steps + `get_weather` tool call + streamed analysis |
| `What time is it?` | Steps + `get_time` tool call + streamed response |

## Key Code

| File | Purpose |
|------|---------|
| `AssistantAgent.java` | Spring `@RestController` — POST `/agui` returns SSE stream |
| `frontend/src/App.tsx` | React app using `atmosphere.js/chat` layout, `fetch()` + `ReadableStream` for SSE |

## AG-UI Wire Format

The endpoint accepts POST and responds with SSE:

```http
POST /agui
Content-Type: application/json

{"threadId":"t1","runId":"r1","messages":[{"role":"user","content":"hello"}]}
```

Response (SSE stream):

```
event: RUN_STARTED
data: {"runId":"r1","threadId":"t1"}

event: STEP_STARTED
data: {"stepId":"step-1","name":"analyze"}

event: TOOL_CALL_START
data: {"toolCallId":"tc-1","name":"get_weather","parentMessageId":"msg-1"}

event: TEXT_MESSAGE_CONTENT
data: {"messageId":"msg-1","delta":"Based on "}

event: RUN_FINISHED
data: {"runId":"r1","threadId":"t1"}
```

## Architecture

```
Browser (React + atmosphere.js/chat)
    │
    │  POST /agui  →  SSE stream back
    ▼
┌─────────────────────────────────┐
│  Spring @RestController         │
│  ├─ Parse RunContext            │
│  ├─ AiEvent → AgUiEvent mapper │
│  └─ Write SSE via PrintWriter   │
├─────────────────────────────────┤
│  AssistantAgent                 │
│  ├─ Agent steps (analyze/respond) │
│  ├─ Tool calls (weather/time)  │
│  └─ Word-by-word text streaming │
└─────────────────────────────────┘
```

## See Also

- [spring-boot-a2a-agent](../spring-boot-a2a-agent/) — A2A protocol (agent ↔ agent)
- [spring-boot-mcp-server](../spring-boot-mcp-server/) — MCP protocol (agent ↔ tools)
- [spring-boot-ai-chat](../spring-boot-ai-chat/) — Atmosphere-native AI streaming
