---
title: "AI Framework Adapters"
description: "Pluggable AiSupport SPI with adapters for Spring AI, LangChain4j, Google ADK, and Embabel"
---

Atmosphere's AI layer is pluggable. The `AiSupport` SPI decouples your endpoint code from any specific AI framework. Drop an adapter JAR on the classpath and `session.stream()` uses it automatically.

## AiSupport SPI

```java
public interface AiSupport {
    boolean isAvailable();
    int priority();
    void configure(LlmSettings settings);
    void stream(AiRequest request, StreamingSession session);
}
```

The framework discovers implementations via `ServiceLoader` and picks the one with the highest `priority()` among those where `isAvailable()` returns `true`.

## Available Adapters

| Classpath JAR | `AiSupport` Implementation | Priority | Backend |
|---------------|---------------------------|----------|---------|
| `atmosphere-ai` (default) | `BuiltInAiSupport` | 0 | `OpenAiCompatibleClient` |
| `atmosphere-spring-ai` | `SpringAiSupport` | 100 | Spring AI `ChatClient` |
| `atmosphere-langchain4j` | `LangChain4jSupport` | 100 | `StreamingChatLanguageModel` |
| `atmosphere-adk` | `AdkSupport` | 100 | Google ADK `Runner` |
| `atmosphere-embabel` | `EmbabelSupport` | 100 | Embabel `AgentPlatform` |

Since all adapters except the built-in have priority 100, whichever is on the classpath wins. If multiple adapters are present, the first one discovered by ServiceLoader takes precedence.

## Built-in Client (Default)

The built-in `OpenAiCompatibleClient` works with any OpenAI-compatible API — Gemini, OpenAI, Ollama, and more.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Configure via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_MODE` | `remote` (cloud API) or `local` (Ollama) | `remote` |
| `LLM_MODEL` | Model name | `gemini-2.5-flash` |
| `LLM_API_KEY` | API key (or `GEMINI_API_KEY`) | — |
| `LLM_BASE_URL` | Override endpoint URL | auto-detected |

No additional dependencies needed. The built-in client is always available as a fallback.

## Spring AI Adapter

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Uses Spring AI's `ChatClient` for streaming. Spring Boot auto-configures the `ChatClient` bean — Atmosphere picks it up automatically.

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
```

Your `@AiEndpoint` code doesn't change — `session.stream()` routes through Spring AI.

## LangChain4j Adapter

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Uses LangChain4j's `StreamingChatLanguageModel`. Configure the model via LangChain4j's Spring Boot starter:

```yaml
langchain4j:
  open-ai:
    streaming-chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
```

## Google ADK Adapter

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Uses Google ADK's `Runner` for agent-based interactions. Configure via ADK's properties:

```yaml
google:
  adk:
    model: gemini-2.5-flash
    api-key: ${GEMINI_API_KEY}
```

## Embabel Adapter

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

Uses Embabel's `AgentPlatform` for multi-agent workflows.

## Swapping Adapters

To switch from one AI backend to another, change only the Maven dependency:

```xml
<!-- Before: LangChain4j -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
</dependency>

<!-- After: Spring AI -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
</dependency>
```

No code changes. Your `@AiEndpoint`, `@AiTool`, and `@Prompt` annotations remain the same.

## Direct Adapter Usage

You can bypass `@AiEndpoint` and use adapters directly for more control:

### Spring AI

```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

### LangChain4j

```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

### Google ADK

```java
var session = StreamingSessions.start(resource);
adkAdapter.stream(new AdkRequest(runner, userId, sessionId, prompt), session);
```

## The Parallel to AsyncSupport

The `AiSupport` SPI mirrors `AsyncSupport` — Atmosphere's transport layer SPI:

| Concern | Transport Layer | AI Layer |
|---------|----------------|----------|
| SPI interface | `AsyncSupport` | `AiSupport` |
| What it adapts | Web containers | AI frameworks |
| Discovery | Classpath scanning | ServiceLoader |
| Resolution | Best container | Highest priority |
| Core method | `service(req, res)` | `stream(request, session)` |
| Fallback | `BlockingIOCometSupport` | `BuiltInAiSupport` |

Same design pattern, same pluggability, same zero-config experience.

## Samples

- [spring-boot-ai-chat](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-ai-chat/) — built-in client
- [spring-boot-spring-ai-chat](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-spring-ai-chat/) — Spring AI
- [spring-boot-langchain4j-chat](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-langchain4j-chat/) — LangChain4j
- [spring-boot-adk-chat](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-adk-chat/) — Google ADK
- [spring-boot-embabel-chat](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-embabel-chat/) — Embabel

## Next Steps

- [Chapter 12: AI Filters & Routing](/docs/tutorial/12-ai-filters/) — PII redaction, cost metering, model routing
- [Chapter 10: @AiTool](/docs/tutorial/10-ai-tools/) — framework-agnostic tool calling
