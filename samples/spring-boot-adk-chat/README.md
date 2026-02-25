# Spring Boot ADK Chat Sample

A real-time AI chat application that streams [Google ADK](https://github.com/google/adk-java) agent responses token-by-token to the browser using Atmosphere's WebSocket transport. **No API key required** — includes a built-in demo agent with simulated responses.

## How It Works

### Server — `AdkChat.java`

A `@ManagedService` endpoint at `/atmosphere/adk-chat`:

1. Client sends a prompt via WebSocket
2. `@Message` handler creates a `StreamingSession` from the connected resource
3. `DemoEventProducer` generates a `Flowable<Event>` stream (simulating an ADK agent)
4. `AdkEventAdapter.bridge()` subscribes to the event stream and broadcasts tokens to all connected clients

```java
@Message
public void onMessage(String prompt) {
    var session = StreamingSessions.start(resource);
    var events = DemoEventProducer.stream(prompt);
    AdkEventAdapter.bridge(events, session);
}
```

### Demo Agent — `DemoEventProducer.java`

Simulates an ADK agent without requiring Gemini API credentials:

- Generates contextual responses based on keywords (`hello`, `atmosphere`, `adk`, `agent`)
- Emits partial ADK `Event` objects word-by-word with 50ms delays
- Ends each response with a `turnComplete` event
- Uses RxJava3 `Flowable` — the same streaming API real ADK agents use

To swap in a real ADK agent backed by Gemini, replace `DemoEventProducer.stream()` with your agent's event stream and set a `GOOGLE_API_KEY` environment variable.

### Client — React + atmosphere.js

A React 19 frontend using shared Atmosphere chat components:

- Connects to `/atmosphere/adk-chat` over WebSocket
- Parses streaming JSON messages (`token`, `progress`, `complete`, `error`)
- Renders tokens as they arrive with a typing indicator
- Uses the `ChatLayout`, `StreamingMessage`, and `ChatInput` components from `atmosphere.js`

## Build & Run

```bash
# From the repository root
./mvnw spring-boot:run -pl samples/spring-boot-adk-chat
```

Open http://localhost:8080 in your browser. No API key needed.

### Rebuilding the Frontend

```bash
cd samples/spring-boot-adk-chat/frontend
npm install
npm run build    # outputs to src/main/resources/static/
```

## Project Structure

```
spring-boot-adk-chat/
├── pom.xml
├── frontend/                        # React + Vite frontend
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx                 # AtmosphereProvider + App
│       └── App.tsx                  # Chat UI with useStreaming hook
└── src/main/
    ├── java/.../adkchat/
    │   ├── AdkChatApplication.java  # Spring Boot entry point
    │   ├── AdkChat.java             # @ManagedService WebSocket endpoint
    │   └── DemoEventProducer.java   # Simulated ADK agent (no API key)
    └── resources/
        └── static/
            ├── index.html           # Built React app
            └── assets/              # Bundled JS
```

## See Also

- [Google ADK for Java](https://github.com/google/adk-java) — the Agent Development Kit
- [AI Chat sample](../spring-boot-ai-chat/) — direct LLM streaming with OpenAI-compatible API
- [LangChain4j sample](../spring-boot-langchain4j-chat/) — LangChain4j adapter
- [Embabel sample](../spring-boot-embabel-chat/) — agentic AI with Embabel
