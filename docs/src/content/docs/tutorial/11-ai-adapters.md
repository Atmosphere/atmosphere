---
title: "AI Framework Adapters"
description: "Plugging Spring AI, LangChain4j, Google ADK, and Embabel into Atmosphere's streaming infrastructure"
sidebar:
  order: 11
---

Atmosphere's AI layer follows the same adapter pattern as its transport layer. Just as Atmosphere auto-detects WebSocket, SSE, or long-polling support at runtime, it auto-detects which AI framework is on the classpath and bridges it to the `StreamingSession` API.

## Built-in LLM Client (zero extra dependencies)

Before reaching for an adapter module, know that `atmosphere-ai` itself includes a built-in `OpenAiCompatibleClient`. This client works with **OpenAI**, **Google Gemini**, **Ollama**, **Azure OpenAI**, and any OpenAI-compatible endpoint -- with zero additional dependencies beyond `atmosphere-ai`.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

Use it directly via `AiConfig`:

```java
var settings = AiConfig.get();
var request = ChatCompletionRequest.builder(settings.model())
        .system("You are a helpful assistant.")
        .user(prompt)
        .build();

settings.client().streamChatCompletion(request, session);
```

The built-in client is what powers `session.stream(message)` inside `@AiEndpoint`. If you need framework-specific features (Spring AI advisors, LangChain4j tool loops, ADK agent orchestration, Embabel agent planning), use one of the adapter modules below.

## Adapter Modules

Four adapter modules ship with Atmosphere 4.0:

| Module | Artifact | AI Framework |
|--------|----------|-------------|
| Spring AI | `atmosphere-spring-ai` | Spring AI (`ChatClient`) |
| LangChain4j | `atmosphere-langchain4j` | LangChain4j (`StreamingChatLanguageModel`) |
| Google ADK | `atmosphere-adk` | Google Agent Development Kit (`Runner`) |
| Embabel | `atmosphere-embabel` | Embabel Agent Framework (`AgentPlatform`) |

All four depend on `atmosphere-ai`, which provides the framework-agnostic interfaces: `AiSupport`, `AiStreamingAdapter<T>`, `StreamingSession`, `AiRequest`, and the `@AiTool`/`@AiEndpoint` annotations.

## The adapter architecture

Two SPIs form the backbone:

**`AiStreamingAdapter<T>`** is the low-level bridge. Each adapter converts one framework-specific request type into `StreamingSession` calls:

```java
public interface AiStreamingAdapter<T> {
    String name();
    void stream(T request, StreamingSession session);
}
```

**`AiSupport`** is the high-level SPI detected by `ServiceLoader`. It accepts a framework-agnostic `AiRequest` and handles model configuration, conversation history, and tool calling internally:

```java
public interface AiSupport {
    String name();
    boolean isAvailable();
    int priority();
    void configure(AiConfig.LlmSettings settings);
    void stream(AiRequest request, StreamingSession session);
    Set<AiCapability> capabilities();
}
```

When multiple `AiSupport` implementations are on the classpath, the one with the highest `priority()` that reports `isAvailable() == true` wins. All four shipped adapters use priority `100`.

## Spring AI adapter

**Module:** `atmosphere-spring-ai`
**Package:** `org.atmosphere.ai.spring`

### Classes

| Class | Role |
|-------|------|
| `SpringAiStreamingAdapter` | Bridges `ChatClient` Flux-based streaming to `StreamingSession`. Supports advisors (RAG, logging, memory) via a customizer callback. |
| `SpringAiSupport` | `AiSupport` implementation backed by `ChatClient`. Capabilities: `TEXT_STREAMING`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `SYSTEM_PROMPT`. |
| `SpringAiToolBridge` | Converts Atmosphere `ToolDefinition` to Spring AI `ToolCallback`. Spring AI handles the tool call loop automatically. |
| `AtmosphereSpringAiAutoConfiguration` | Spring Boot `@AutoConfiguration`. Activates when `ChatClient` is on the classpath. |

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

### Auto-configuration

The auto-configuration creates a `SpringAiStreamingAdapter` bean and, if a `ChatClient` bean exists, wires it into `SpringAiSupport`:

```java
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
public class AtmosphereSpringAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringAiStreamingAdapter springAiStreamingAdapter() {
        return new SpringAiStreamingAdapter();
    }

    @Bean
    @ConditionalOnBean(ChatClient.class)
    SpringAiSupport springAiSupportBridge(ChatClient chatClient) {
        SpringAiSupport.setChatClient(chatClient);
        return new SpringAiSupport();
    }
}
```

### Direct adapter usage

For fine-grained control, use `SpringAiStreamingAdapter` directly:

```java
var session = StreamingSessions.start(resource);
adapter.stream(chatClient, "Tell me about Atmosphere", session);
```

With advisors:

```java
adapter.stream(chatClient, "Tell me about Atmosphere", session,
    spec -> spec.advisors(myRagAdvisor, myLoggingAdvisor)
                .system("You are a helpful assistant"));
```

### How SpringAiSupport works

When an `@AiEndpoint` receives a message, `SpringAiSupport.stream()` runs the following sequence:

1. Build a prompt spec from the `AiRequest` (system prompt, conversation history, user message).
2. If the request includes `@AiTool` definitions, convert them to Spring AI `ToolCallback` instances via `SpringAiToolBridge.toToolCallbacks()` and attach them with `promptSpec.toolCallbacks(callbacks)`.
3. Subscribe to the `Flux<ChatResponse>` from `promptSpec.stream().chatResponse()`.
4. For each response chunk, extract the text via `response.getResult().getOutput().getText()` and forward it to `session.send()`.
5. On Flux completion, call `session.complete()`. On error, call `session.error()`.

## LangChain4j adapter

**Module:** `atmosphere-langchain4j`
**Package:** `org.atmosphere.ai.langchain4j`

### Classes

| Class | Role |
|-------|------|
| `LangChain4jStreamingAdapter` | Bridges `StreamingChatLanguageModel` to `StreamingSession`. |
| `AtmosphereStreamingResponseHandler` | Simple `StreamingChatResponseHandler`: forwards streaming texts via `session.send()`, completion via `session.complete()`. |
| `ToolAwareStreamingResponseHandler` | Extends the basic handler with tool calling support. Executes tools via `LangChain4jToolBridge` and re-submits conversations. Max 5 tool rounds. |
| `LangChain4jAiSupport` | `AiSupport` implementation. Capabilities: `TEXT_STREAMING`, `TOOL_CALLING`, `SYSTEM_PROMPT`. |
| `LangChain4jToolBridge` | Converts `ToolDefinition` to `ToolSpecification` and handles tool execution. |
| `AtmosphereLangChain4jAutoConfiguration` | Activates when `StreamingChatLanguageModel` is on the classpath. |

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

### Configuration example

From the `spring-boot-langchain4j-chat` sample:

```java
@Configuration
public class LlmConfig {
    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gemini-2.5-flash}") String model) {
        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel(AiConfig.LlmSettings settings) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.client().apiKey())
                .modelName(settings.model())
                .build();
    }
}
```

The auto-configuration picks up the `StreamingChatLanguageModel` bean:

```java
@AutoConfiguration
@ConditionalOnClass(name = "dev.langchain4j.model.chat.StreamingChatLanguageModel")
public class AtmosphereLangChain4jAutoConfiguration {

    @Bean
    @ConditionalOnBean(StreamingChatLanguageModel.class)
    LangChain4jAiSupport langChain4jAiSupportBridge(StreamingChatLanguageModel model) {
        LangChain4jAiSupport.setModel(model);
        return new LangChain4jAiSupport();
    }
}
```

### Tool calling with LangChain4j

Unlike Spring AI, LangChain4j does not execute tool callbacks automatically. When the model responds with `ToolExecutionRequest` objects instead of text, `ToolAwareStreamingResponseHandler` handles the loop:

1. Receive the `AiMessage` with tool execution requests in `onCompleteResponse()`.
2. Call `LangChain4jToolBridge.executeToolCalls()` to run each tool and collect `ToolExecutionResultMessage` objects.
3. Append the AI message and tool results to the conversation history.
4. Re-submit the updated conversation to the model with a new `ToolAwareStreamingResponseHandler`.
5. Repeat until the model produces a text response or `MAX_TOOL_ROUNDS` (5) is reached.

## Google ADK adapter

**Module:** `atmosphere-adk`
**Package:** `org.atmosphere.ai.adk`

### Classes

| Class | Role |
|-------|------|
| `AdkStreamingAdapter` | Bridges ADK `Runner.runAsync()` RxJava `Flowable<Event>` to `StreamingSession` via `AdkEventAdapter`. |
| `AdkAiSupport` | `AiSupport` implementation. Capabilities: `TEXT_STREAMING`, `TOOL_CALLING`, `AGENT_ORCHESTRATION`, `CONVERSATION_MEMORY`, `SYSTEM_PROMPT`. |
| `AdkToolBridge` | Converts `ToolDefinition` to ADK `BaseTool`. Each tool extends `BaseTool` with `runAsync()` that delegates to the Atmosphere `ToolExecutor`. |
| `AdkBroadcastTool` | Ready-made `BaseTool` that lets an ADK agent broadcast messages to Atmosphere clients. |
| `AdkEventAdapter` | Subscribes to a `Flowable<Event>` and forwards partial streaming texts, turn completions, and errors to a `StreamingSession`. |
| `AtmosphereAdkAutoConfiguration` | Activates when `com.google.adk.runner.Runner` is on the classpath. |

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
```

### ADK-specific details

ADK requires tools to be registered at agent construction time. You cannot add tools dynamically per-request. Use `AdkAiSupport.configureWithTools()`:

```java
var tools = List.of(weatherTool, calendarTool);
AdkAiSupport.configureWithTools(settings, tools);
```

ADK only supports Gemini models natively. If you configure a non-Gemini model, `AdkAiSupport` logs a warning suggesting `atmosphere-spring-ai` or `atmosphere-langchain4j` instead.

### AdkBroadcastTool

The `AdkBroadcastTool` lets an ADK agent push messages directly to browser clients:

```java
// Fixed topic -- agent broadcasts to one specific broadcaster
var broadcastTool = new AdkBroadcastTool(broadcaster);

// Or dynamic topic -- agent specifies the topic at call time
var broadcastTool = new AdkBroadcastTool(broadcasterFactory);

LlmAgent agent = LlmAgent.builder()
    .name("assistant")
    .model("gemini-2.0-flash")
    .instruction("Use the broadcast tool to push updates to users")
    .tools(broadcastTool)
    .build();
```

When called by the agent, `AdkBroadcastTool.runAsync()` broadcasts the message and returns a map containing `status`, `topic`, and `recipients` count.

### AdkEventAdapter

`AdkEventAdapter` bridges ADK's RxJava `Flowable<Event>` to Atmosphere:

```java
Flowable<Event> events = runner.runAsync(userId, sessionId, userMessage);
AdkEventAdapter adapter = AdkEventAdapter.bridge(events, broadcaster);
```

The adapter handles three event types:
- **Partial events** (`event.partial() == true`): text content forwarded via `session.send()`.
- **Turn completion** (`event.turnComplete() == true`): triggers `session.complete()`.
- **Error events** (`event.errorMessage().isPresent()`): triggers `session.error()`.

## Embabel adapter

**Module:** `atmosphere-embabel`
**Package:** `org.atmosphere.ai.embabel`

Embabel is a Kotlin-based agent framework with built-in planning, tool calling, and orchestration. The `atmosphere-embabel` adapter bridges Embabel's `OutputChannel` pattern to `StreamingSession`, streaming agent events (thinking, tool calls, results) to the browser.

### Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>LATEST</version> <!-- check Maven Central for latest -->
</dependency>
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-platform-autoconfigure</artifactId>
    <version>4.0.13</version>
</dependency>
```

### Usage

Define an Embabel agent:

```java
@Agent(name = "chat-assistant", description = "Answers user questions")
public class ChatAssistantAgent {
    @Action(description = "Answer the user's question")
    public String answer(String userMessage) {
        return "Answer clearly and concisely: " + userMessage;
    }
}
```

Run it through Atmosphere:

```java
var session = StreamingSessions.start(resource);
var agent = agentPlatform.agents().stream()
        .filter(a -> "chat-assistant".equals(a.getName()))
        .findFirst().orElseThrow();

var agentRequest = new AgentRequest("chat-assistant", channel -> {
    var options = ProcessOptions.DEFAULT.withOutputChannel(channel);
    agentPlatform.runAgentFrom(agent, options, Map.of("userMessage", prompt));
    return Unit.INSTANCE;
});
Thread.startVirtualThread(() -> adapter.stream(agentRequest, session));
```

`AtmosphereOutputChannel` translates agent events (thinking, tool calls, results) into `StreamingSession` calls with appropriate `progress` / `streaming-text` / `complete` messages.

## The @AiTool annotation

All four adapters share the same framework-agnostic tool definition via `@AiTool` (in `org.atmosphere.ai.annotation`):

```java
@AiTool(name = "get_weather", description = "Get current weather for a city")
public WeatherResult getWeather(@Param("city") String city,
                                @Param(value = "unit", required = false) String unit) {
    return weatherService.lookup(city, unit);
}
```

The `ToolRegistry` discovers `@AiTool` methods at startup and creates `ToolDefinition` objects. Each adapter's tool bridge then converts these into the native format:

| Adapter | Bridge class | Native tool type |
|---------|-------------|-----------------|
| Spring AI | `SpringAiToolBridge` | `ToolCallback` |
| LangChain4j | `LangChain4jToolBridge` | `ToolSpecification` |
| Google ADK | `AdkToolBridge` | `BaseTool` |
| Embabel | _(handled by Embabel platform)_ | Embabel `@Action` |

Tools are registered globally and selected per-endpoint:

```java
@AiEndpoint(path = "/chat", tools = {WeatherTools.class, CalendarTools.class})
```

## Choosing an adapter

**Spring AI** is the best fit for Spring Boot applications already using the Spring AI ecosystem. It supports advisors for RAG, logging, and memory, and has the broadest model provider support via Spring AI's model starters. Spring AI handles the tool call loop automatically.

**LangChain4j** is the best fit for applications that need the LangChain4j tool ecosystem or need to work with multiple model providers without Spring. The explicit tool execution loop (via `ToolAwareStreamingResponseHandler`) gives you full control over each tool round.

**Google ADK** is the best fit for multi-agent orchestration with Gemini models. It has built-in conversation memory and agent chaining. The `AdkBroadcastTool` makes it straightforward for agents to push real-time updates to browser clients. Note that ADK requires tools at agent construction time, not per-request.

**Embabel** is the best fit for Kotlin-based agent applications that need Embabel's planning and orchestration capabilities. The `AtmosphereOutputChannel` translates agent lifecycle events into streaming texts automatically.

All four adapters produce the same wire protocol on the Atmosphere side: text-by-text JSON messages delivered over WebSocket, SSE, or long-polling to any connected client.

## Samples

Each adapter has a corresponding sample application:

| Sample | Adapter | Run command |
|--------|---------|-------------|
| `samples/spring-boot-spring-ai-chat/` | Spring AI | `./mvnw spring-boot:run -pl samples/spring-boot-spring-ai-chat` |
| `samples/spring-boot-spring-ai-routing/` | Spring AI (multi-model routing) | `./mvnw spring-boot:run -pl samples/spring-boot-spring-ai-routing` |
| `samples/spring-boot-langchain4j-chat/` | LangChain4j | `./mvnw spring-boot:run -pl samples/spring-boot-langchain4j-chat` |
| `samples/spring-boot-adk-chat/` | Google ADK | `./mvnw spring-boot:run -pl samples/spring-boot-adk-chat` |
| `samples/spring-boot-adk-tools/` | Google ADK (with tools) | `./mvnw spring-boot:run -pl samples/spring-boot-adk-tools` |
| `samples/spring-boot-embabel-chat/` | Embabel | `./mvnw spring-boot:run -pl samples/spring-boot-embabel-chat` |
| `samples/spring-boot-embabel-horoscope/` | Embabel (agent planning) | `./mvnw spring-boot:run -pl samples/spring-boot-embabel-horoscope` |

All samples share the same browser client and produce the same AI streaming wire protocol.
