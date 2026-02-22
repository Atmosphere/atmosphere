# Spring Boot Embabel Agent Chat Sample

A real-time AI agent chat using the **Embabel Agent Framework** and Atmosphere. Embabel agents handle planning, tool calling, and orchestration — Atmosphere streams the agent events (progress, tool calls, tokens) to the browser over WebSocket in real time.

## How It Works

### Agent — `ChatAssistantAgent.java`

An Embabel `@Agent` class defines the agent's behavior:

```java
@Agent(name = "chat-assistant",
       description = "A helpful chat assistant that answers user questions")
public class ChatAssistantAgent {

    @Action(description = "Answer the user's question")
    public String answer(String userMessage) {
        return "Answer the following question clearly and concisely: " + userMessage;
    }
}
```

### Server — `EmbabelChat.java` + `AgentRunner.java`

A `@ManagedService` endpoint at `/atmosphere/embabel-chat`:

1. Client sends a prompt via WebSocket
2. `@Message` handler delegates to `AgentRunner` which creates a `StreamingSession`
3. `AgentRunner` looks up the `chat-assistant` agent on the Embabel `AgentPlatform`
4. Calls `agentPlatform.runAgentFrom()` with an `AtmosphereOutputChannel` — agent events stream to the browser

```java
var session = StreamingSessions.start(resource);
var agent = agentPlatform.agents().stream()
        .filter(a -> "chat-assistant".equals(a.getName()))
        .findFirst().orElseThrow();

var agentRequest = new AgentRequest("chat-assistant", channel -> {
    var options = ProcessOptions.DEFAULT.withOutputChannel(channel);
    agentPlatform.runAgentFrom(agent, options, Map.of("userMessage", userMessage));
    return Unit.INSTANCE;
});
Thread.startVirtualThread(() -> ADAPTER.stream(agentRequest, session));
```

### Client — `index.html`

Same streaming UI as the other AI samples — connects over WebSocket, renders tokens and progress events.

## Configuration

The Embabel platform auto-configures via Spring AI. Set your LLM API key:

```bash
export OPENAI_API_KEY=sk-...
# Or use any OpenAI-compatible provider:
export LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
export LLM_API_KEY=AIza...
export LLM_MODEL=gemini-2.5-flash
```

## Build & Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-embabel-chat
```

Open http://localhost:8082 in your browser.

## Project Structure

```
spring-boot-embabel-chat/
├── pom.xml
└── src/main/
    ├── java/.../embabelchat/
    │   ├── EmbabelChatApplication.java   # Spring Boot entry point
    │   ├── EmbabelChat.java              # @ManagedService endpoint
    │   ├── ChatAssistantAgent.java       # @Agent definition
    │   ├── AgentRunner.java              # AgentPlatform bridge
    │   └── LlmConfig.java               # Configuration
    └── resources/
        ├── application.yml
        └── static/
            ├── index.html
            └── assets/                    # Bundled atmosphere.js client
```

## See Also

- [AI / LLM Streaming Guide](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming)
- [AI chat sample](../spring-boot-ai-chat/) — built-in LLM client
- [LangChain4j sample](../spring-boot-langchain4j-chat/) — LangChain4j adapter
