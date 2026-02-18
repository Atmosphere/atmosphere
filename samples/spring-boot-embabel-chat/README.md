# Spring Boot Embabel Agent Chat Sample

A real-time AI chat application using the **Embabel Agent Framework**, streaming agent events (thinking, tool calls, results) to the browser via Atmosphere WebSocket.

## How It Works

### Server — `EmbabelChat.java` + `AgentRunner.java`

A `@ManagedService` endpoint at `/atmosphere/embabel-chat`:

1. Client sends a prompt via WebSocket
2. `@Message` handler delegates to `AgentRunner` which creates a `StreamingSession`
3. `AtmosphereOutputChannel` bridges Embabel's `OutputChannel` to the session
4. Agent progress events stream as `progress` messages; final output as `token` messages

```java
@Message
public void onMessage(String prompt) {
    AgentRunner.run(prompt, resource);
}
```

The `AgentRunner` creates the Embabel platform and runs the agent:

```java
var session = StreamingSessions.start(resource);
var channel = AtmosphereOutputChannel(session);
Thread.startVirtualThread(() -> agentPlatform.runAgent(prompt, outputChannel = channel));
```

### Client — `index.html`

Same streaming UI as the other AI samples — connects over WebSocket, renders tokens and progress events.

## Configuration

Same environment variables as the [AI chat sample](../spring-boot-ai-chat/):

```bash
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...
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
    │   ├── AgentRunner.java              # Embabel agent runner
    │   └── LlmConfig.java               # AiConfig setup
    └── resources/
        ├── application.yml
        └── static/
            ├── index.html
            └── javascript/atmosphere.js
```

## See Also

- [AI / LLM Streaming Guide](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming)
- [AI chat sample](../spring-boot-ai-chat/) — built-in LLM client
- [LangChain4j sample](../spring-boot-langchain4j-chat/) — LangChain4j adapter
