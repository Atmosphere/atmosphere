# Atmosphere + Spring AI Chat

Real-time AI chat using **Spring AI's ChatClient** with **Atmosphere's SpringAiStreamingAdapter** for WebSocket streaming.

## What This Demonstrates

This sample shows the bridge between Spring AI and Atmosphere:

```
User → WebSocket → @ManagedService → SpringAiStreamingAdapter
                                        ↓
                                    ChatClient.prompt(msg).stream()
                                        ↓
                                    Flux<ChatResponse> → StreamingSession.send(token)
                                        ↓
                                    WebSocket ← token-by-token to browser
```

**Key point:** You keep your Spring AI code (`ChatClient`, Advisors, tools) and get real-time WebSocket push for free via `atmosphere-spring-ai`.

## Running

```bash
# From the repository root
cd samples/spring-boot-spring-ai-chat

# Without API key (demo mode — simulated streaming)
mvn spring-boot:run

# With OpenAI
export OPENAI_API_KEY=sk-...
mvn spring-boot:run

# With Gemini (OpenAI-compatible)
export OPENAI_API_KEY=AIza...
export SPRING_AI_OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=gemini-2.5-flash
mvn spring-boot:run
```

Open http://localhost:8083 in your browser.

## Key Code

### The Handler (`SpringAiChat.java`)

```java
@Message
public void onMessage(String userMessage) {
    var session = StreamingSessions.start(resource);
    var chatClient = SpringBeanAccessor.getBean(ChatClient.Builder.class).build();
    var adapter = SpringBeanAccessor.getBean(SpringAiStreamingAdapter.class);
    adapter.stream(chatClient, userMessage, session);
}
```

Three lines to bridge Spring AI to real-time WebSocket streaming:
1. Create an Atmosphere `StreamingSession`
2. Get Spring AI's `ChatClient`
3. Stream via the `SpringAiStreamingAdapter`

### Dependencies

```xml
<!-- Atmosphere with Spring AI adapter -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
</dependency>

<!-- Spring AI OpenAI starter (or any model provider) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

## Architecture

| Component | Role |
|-----------|------|
| `atmosphere-spring-ai` | Bridges `Flux<ChatResponse>` → `StreamingSession` |
| `SpringAiStreamingAdapter` | Auto-configured bean that does the bridging |
| `ChatClient` | Spring AI's fluent API for LLM interactions |
| `StreamingSession` | Atmosphere's token-by-token push to browsers |

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OPENAI_API_KEY` | API key for OpenAI or compatible provider | `demo` (demo mode) |
| `SPRING_AI_OPENAI_BASE_URL` | Override API endpoint (for Gemini, etc.) | OpenAI default |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | Model name | `gpt-4o-mini` |
