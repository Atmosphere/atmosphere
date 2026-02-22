# Spring Boot AI Chat Sample

A real-time AI chat application that streams LLM responses token-by-token to the browser using Atmosphere's built-in `OpenAiCompatibleClient`. Works with **Gemini**, **OpenAI**, **Ollama**, and any OpenAI-compatible endpoint.

## How It Works

### Server — `AiChat.java`

A `@ManagedService` endpoint at `/atmosphere/ai-chat`:

1. Client sends a prompt via WebSocket
2. `@Message` handler creates a `StreamingSession` and a `ChatCompletionRequest`
3. A virtual thread streams the LLM response, pushing tokens through the session
4. Each token is written directly to the WebSocket as JSON

```java
@Message
public void onMessage(String prompt) {
    var settings = AiConfig.get();
    var session = StreamingSessions.start(resource);
    var request = ChatCompletionRequest.builder(settings.model())
            .system("You are a helpful assistant.")
            .user(prompt)
            .build();
    Thread.startVirtualThread(() -> settings.client().streamChatCompletion(request, session));
}
```

### Client — `index.html`

A single-page HTML/JS app using `atmosphere.js`:

- Connects to `/atmosphere/ai-chat` over WebSocket
- Parses streaming JSON messages (`token`, `progress`, `complete`, `error`)
- Renders tokens as they arrive with a typing indicator
- Supports markdown rendering of AI responses

## Configuration

Set environment variables before running:

```bash
# Gemini (default)
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...

# OpenAI
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-...

# Ollama (local)
export LLM_MODE=local
export LLM_MODEL=llama3.2
```

## Build & Run

```bash
# From the repository root
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat

# Or build a JAR
./mvnw package -pl samples/spring-boot-ai-chat -DskipTests
java -jar samples/spring-boot-ai-chat/target/*.jar
```

Open http://localhost:8080 in your browser.

## Project Structure

```
spring-boot-ai-chat/
├── pom.xml
└── src/main/
    ├── java/.../aichat/
    │   ├── AiChatApplication.java    # Spring Boot entry point
    │   ├── AiChat.java               # @ManagedService WebSocket endpoint
    │   └── LlmConfig.java            # @Configuration bridging Spring properties to AiConfig
    └── resources/
        ├── application.yml           # Atmosphere + LLM config
        └── static/
            ├── index.html            # Chat UI
            └── assets/               # Bundled atmosphere.js client
```

## See Also

- [AI / LLM Streaming Guide](https://github.com/Atmosphere/atmosphere/wiki/AI-LLM-Streaming)
- [LangChain4j sample](../spring-boot-langchain4j-chat/) — same app with LangChain4j adapter
- [Embabel sample](../spring-boot-embabel-chat/) — agentic AI with Embabel
