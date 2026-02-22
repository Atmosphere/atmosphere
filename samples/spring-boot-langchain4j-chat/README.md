# Spring Boot LangChain4j Chat Sample

A real-time AI chat application that streams LLM responses to the browser using **LangChain4j**'s streaming API, bridged to Atmosphere WebSocket via the `atmosphere-langchain4j` adapter.

## How It Works

### Server — `LangChain4jChat.java`

A `@ManagedService` endpoint at `/atmosphere/langchain4j-chat`:

1. Client sends a prompt via WebSocket
2. `@Message` handler creates a `StreamingSession` and a LangChain4j `ChatRequest`
3. `LangChain4jStreamingAdapter` bridges LangChain4j's callback API to the session
4. `AtmosphereStreamingResponseHandler` converts `onNext`/`onComplete`/`onError` to session calls

```java
@Message
public void onMessage(String prompt) {
    var session = StreamingSessions.start(resource);
    var chatRequest = ChatRequest.builder()
            .messages(List.of(UserMessage.from(prompt)))
            .build();
    Thread.startVirtualThread(() -> adapter.stream(model, chatRequest, session));
}
```

### Client — `index.html`

Same streaming UI as the AI chat sample — connects over WebSocket, renders tokens as they arrive.

## Configuration

Same environment variables as the [AI chat sample](../spring-boot-ai-chat/):

```bash
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...
```

## Build & Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-langchain4j-chat
```

Open http://localhost:8081 in your browser.

## Project Structure

```
spring-boot-langchain4j-chat/
├── pom.xml
└── src/main/
    ├── java/.../langchain4jchat/
    │   ├── LangChain4jChatApplication.java   # Spring Boot entry point
    │   ├── LangChain4jChat.java              # @ManagedService endpoint
    │   └── LlmConfig.java                    # @Configuration: LangChain4j model + AiConfig beans
    └── resources/
        ├── application.yml
        └── static/
            ├── index.html
            └── javascript/atmosphere.js
```

## See Also

- [AI / LLM Streaming Guide](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming)
- [AI chat sample](../spring-boot-ai-chat/) — built-in LLM client (no framework dependency)
- [Embabel sample](../spring-boot-embabel-chat/) — agentic AI with Embabel
