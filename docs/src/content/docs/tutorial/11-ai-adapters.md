---
title: "AI Framework Adapters"
description: "How AiSupport auto-detects Spring AI, LangChain4j, Google ADK, Embabel, or the built-in client"
---

In [Chapter 9](/docs/tutorial/09-ai-endpoint/) and [Chapter 10](/docs/tutorial/10-ai-tools/) you used `session.stream(message)` and the framework "auto-detected" the AI backend. This chapter pulls back the curtain on that mechanism: the `AiSupport` SPI, the five built-in adapters, and how you switch between them -- or bypass them entirely for direct control.

## The AiSupport SPI

`AiSupport` is a service-provider interface discovered via `java.util.ServiceLoader`. It is the AI equivalent of `AsyncSupport` (which adapts web containers). Each implementation wraps a specific AI framework.

```java
public interface AiSupport {

    /** Can this adapter be used? Typically checks classpath and config. */
    boolean isAvailable();

    /** Higher priority wins. Built-in is 0; framework adapters are 100. */
    int priority();

    /** One-time configuration with LLM settings. */
    void configure(LlmSettings settings);

    /** Stream a response for the given request to the given session. */
    void stream(AiRequest request, StreamingSession session);
}
```

### Resolution Algorithm

At startup, `AtmosphereFramework` loads all `AiSupport` implementations via `ServiceLoader`, filters to those where `isAvailable()` returns `true`, and selects the one with the highest `priority()`. If two have the same priority, the first discovered wins (classpath order).

In practice, you will have at most one framework adapter on the classpath alongside the built-in client. If you have none, the built-in client (priority 0) is the fallback.

### The Parallel to AsyncSupport

The `AiSupport` SPI mirrors `AsyncSupport`, the transport layer SPI:

| Concern | Transport Layer | AI Layer |
|---------|----------------|----------|
| SPI interface | `AsyncSupport` | `AiSupport` |
| What it adapts | Web containers (Jetty, Tomcat, Undertow) | AI frameworks (Spring AI, LangChain4j, ADK, Embabel) |
| Discovery | Classpath scanning | `ServiceLoader` |
| Resolution | Best available container | Highest `priority()` among `isAvailable()` |
| Initialization | `init(ServletConfig)` | `configure(LlmSettings)` |
| Core method | `service(req, res)` | `stream(AiRequest, StreamingSession)` |
| Fallback | `BlockingIOCometSupport` | `BuiltInAiSupport` (OpenAI-compatible) |

Same design pattern, same pluggability, same zero-config experience.

## Adapter Overview

| Classpath JAR | `AiSupport` Implementation | Priority | Backend |
|---------------|---------------------------|----------|---------|
| `atmosphere-ai` (default) | `BuiltInAiSupport` | 0 | `OpenAiCompatibleClient` |
| `atmosphere-spring-ai` | `SpringAiSupport` | 100 | Spring AI `ChatClient` |
| `atmosphere-langchain4j` | `LangChain4jAiSupport` | 100 | `StreamingChatLanguageModel` |
| `atmosphere-adk` | `AdkAiSupport` | 100 | Google ADK `Runner` |
| `atmosphere-embabel` | `EmbabelAiSupport` | 100 | Embabel `AgentPlatform` |

## Built-in: OpenAiCompatibleClient (Priority 0)

The default adapter. Always available because it ships with `atmosphere-ai`. It uses a lightweight HTTP client that speaks the OpenAI chat completions API, which is supported by many providers.

**Maven:**

```xml
<!-- Included automatically with atmosphere-ai -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

**Supported providers:**

| Provider | Model examples | API key variable |
|----------|---------------|-----------------|
| Google Gemini | `gemini-2.5-flash`, `gemini-2.5-pro` | `GEMINI_API_KEY` or `LLM_API_KEY` |
| OpenAI | `gpt-4o`, `gpt-4o-mini`, `o3-mini` | `LLM_API_KEY` |
| Ollama (local) | `llama3.2`, `mistral`, `codellama` | None (local) |
| Any OpenAI-compatible | Any | `LLM_API_KEY` + `LLM_BASE_URL` |

**Configuration:**

```bash
export LLM_MODE=remote       # or "local" for Ollama
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...
```

**How it streams internally:**

```java
// Internally, BuiltInAiSupport does roughly this:
var client = OpenAiCompatibleClient.create(settings);
var request = ChatCompletionRequest.builder(settings.model())
    .system(aiRequest.systemPrompt())
    .messages(aiRequest.history())
    .user(aiRequest.message())
    .tools(aiRequest.tools())  // @AiTool definitions as OpenAI function specs
    .stream(true)
    .build();

client.streamChatCompletion(request, new StreamingCallback() {
    public void onToken(String token) { session.send(token); }
    public void onToolCall(String name, Map<String, Object> args) {
        var result = toolExecutor.execute(name, args);
        // Feed result back and continue streaming
    }
    public void onComplete() { session.complete(); }
});
```

**When to use:** Quick prototyping, small projects, or when you want zero extra dependencies. It handles tool calling, streaming, and conversation memory out of the box.

## Spring AI Adapter (Priority 100)

Wraps Spring AI's `ChatClient` for streaming. Use this if your project already uses Spring AI advisors, vector stores, or the broader Spring AI ecosystem.

**Maven:**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

**How it works:**

`SpringAiSupport` checks for a `ChatClient` bean in the Spring application context. If found, it delegates streaming to `SpringAiStreamingAdapter`, which uses `ChatClient.prompt().stream()` and maps each `ChatResponse` chunk to `session.send(token)`.

**Spring Boot configuration:**

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

**Auto-configuration:**

`AtmosphereSpringAiAutoConfiguration` creates:

- A `SpringAiStreamingAdapter` bean.
- A `SpringAiSupport` bridge bean that connects the Spring-managed `ChatClient` to the `AiSupport` SPI.

**Your code does not change:**

```java
@AiEndpoint(path = "/ai/chat", systemPrompt = "You are a helpful assistant")
public class MyChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // uses Spring AI ChatClient automatically
    }
}
```

**Direct usage (bypassing @AiEndpoint):**

```java
var session = StreamingSessions.start(resource);
springAiAdapter.stream(chatClient, prompt, session);
```

With advisors:

```java
springAiAdapter.stream(chatClient, prompt, session, myRagAdvisor);
```

With a customizer:

```java
springAiAdapter.stream(chatClient, prompt, session, spec -> {
    spec.system("Custom system prompt for this call");
});
```

**Tool integration:** `@AiTool` methods are converted to `ToolCallback` objects via `SpringAiToolBridge`. They are registered with the `ChatClient` prompt automatically.

## LangChain4j Adapter (Priority 100)

Wraps LangChain4j's `StreamingChatLanguageModel`.

**Maven:**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

**Configuration:**

```yaml
# application.yml
langchain4j:
  open-ai:
    streaming-chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
```

**How it works:**

`LangChain4jAiSupport` checks if `StreamingChatLanguageModel` is on the classpath. It delegates streaming to `LangChain4jStreamingAdapter`, which uses `model.chat()` with an `AtmosphereStreamingResponseHandler`.

**Callback mapping:**

| LangChain4j Callback | StreamingSession Action |
|----------------------|------------------------|
| `onPartialResponse(text)` | `session.send(text)` |
| `onCompleteResponse(response)` | `session.complete()` |
| `onError(throwable)` | `session.error(message)` |

**Direct usage:**

```java
var session = StreamingSessions.start(resource);
model.chat(ChatMessage.userMessage(prompt),
    new AtmosphereStreamingResponseHandler(session));
```

**Tool integration:** `@AiTool` methods are converted to `ToolSpecification` objects via `LangChain4jToolBridge`.

## Google ADK Adapter (Priority 100)

Bridges Google Agent Development Kit (ADK) agent streams to Atmosphere.

**Maven:**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

**Configuration:**

```yaml
google:
  adk:
    model: gemini-2.0-flash
    api-key: ${GEMINI_API_KEY}
```

**Architecture:**

```
Browser  <-- WS/SSE -->  Broadcaster  <-- AdkEventAdapter  <-- Flowable<Event>  <-- Runner  <-- LlmAgent
```

**How it works:**

`AdkAiSupport` checks for the ADK `Runner` class on the classpath. It delegates to `AdkStreamingAdapter`, which:

1. Creates a session with the ADK `Runner`.
2. Subscribes to the `Flowable<Event>` stream from the runner.
3. `AdkEventAdapter` maps each ADK `Event` to `session.send(token)` or `session.sendProgress(message)`.
4. Calls `session.complete()` when the event stream completes.

**Direct usage:**

```java
LlmAgent agent = LlmAgent.builder()
    .name("assistant")
    .model("gemini-2.0-flash")
    .instruction("You are a helpful assistant.")
    .build();

Runner runner = Runner.builder()
    .agent(agent)
    .appName("my-app")
    .build();

var session = StreamingSessions.start(resource);
adkAdapter.stream(new AdkRequest(runner, userId, sessionId, prompt), session);
```

**Low-level event bridge:**

```java
Flowable<Event> events = runner.runAsync(userId, sessionId,
    Content.fromParts(Part.fromText(prompt)));
AdkEventAdapter.bridge(events, broadcaster);
```

**ADK broadcast tool -- give an ADK agent a tool that pushes to browsers:**

```java
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcaster);

LlmAgent agent = LlmAgent.builder()
    .name("notifier")
    .model("gemini-2.0-flash")
    .instruction("Use the broadcast tool to send updates to users.")
    .tools(broadcastTool)
    .build();
```

**Tool integration:** `@AiTool` methods are converted to `BaseTool` instances via `AdkToolBridge`.

## Embabel Adapter (Priority 100)

Wraps Embabel's `AgentPlatform` for running multi-step agents with streaming output.

**Maven:**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

**How it works:**

`EmbabelAiSupport` checks for the Embabel `AgentPlatform` class. It delegates to `EmbabelStreamingAdapter`, which routes Embabel `OutputChannelEvent` objects to the `StreamingSession`.

**Event mapping:**

| Embabel Event | StreamingSession Action |
|---------------|------------------------|
| `MessageOutputChannelEvent` | `session.send(content)` |
| `ContentOutputChannelEvent` | `session.send(content)` |
| `ProgressOutputChannelEvent` | `session.sendProgress(message)` |
| `LoggingOutputChannelEvent` (INFO+) | `session.sendProgress(message)` |

**Direct usage (Kotlin):**

```kotlin
val session = StreamingSessions.start(resource)
embabelAdapter.stream(AgentRequest("assistant") { channel ->
    agentPlatform.run(prompt, channel)
}, session)
```

**Agent name from hints:**

The agent name can be specified in `AiRequest.hints()["agentName"]`, allowing different `@AiEndpoint` paths to run different Embabel agents.

## How to Swap Adapters

Swapping is a matter of changing the Maven dependency. Your `@AiEndpoint`, `@Prompt`, and `@AiTool` code remains unchanged.

### From built-in to Spring AI

```xml
<!-- Add this dependency -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>${atmosphere.version}</version>
</dependency>

<!-- Plus a Spring AI model provider -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

Configure the model in `application.properties`:

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o
```

Your endpoint code stays exactly the same. The `SpringAiSupport` (priority 100) takes over from `BuiltInAiSupport` (priority 0).

### From Spring AI to LangChain4j

```xml
<!-- Remove atmosphere-spring-ai, add: -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>${atmosphere.version}</version>
</dependency>

<!-- Plus a LangChain4j model provider -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
</dependency>
```

### From LangChain4j to Google ADK

```xml
<!-- Remove atmosphere-langchain4j, add: -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

### Decision Matrix

| Criteria | Recommended Adapter |
|----------|-------------------|
| Quick start, minimal dependencies | Built-in (`atmosphere-ai`) |
| Already using Spring AI advisors / vector stores | Spring AI (`atmosphere-spring-ai`) |
| Need LangChain4j RAG pipelines or memory | LangChain4j (`atmosphere-langchain4j`) |
| Building multi-agent systems with Google tools | ADK (`atmosphere-adk`) |
| Running Embabel agents with step tracking | Embabel (`atmosphere-embabel`) |
| Need multiple backends simultaneously | Use `RoutingLlmClient` ([Chapter 12](/docs/tutorial/12-ai-filters/)) |

## Direct Adapter Usage -- Bypassing @AiEndpoint

Sometimes you need more control than `@AiEndpoint` provides. You can use adapters directly in a `@ManagedService` or a REST controller.

### In a @ManagedService

```java
@ManagedService(path = "/custom-chat")
public class CustomChat {

    @Inject
    private AtmosphereResource resource;

    @Message
    public void onMessage(String message) {
        var session = StreamingSessions.start(resource);

        // Add custom headers, metadata, or preprocessing
        session.sendMetadata(Map.of("timestamp", Instant.now().toString()));
        session.sendProgress("Preparing response...");

        // Use the AiSupport SPI directly
        var ai = AiSupportResolver.resolve(resource.getAtmosphereConfig());
        var request = AiRequest.builder()
            .message(message)
            .systemPrompt("You are a coding assistant")
            .model("gpt-4o")
            .build();

        ai.stream(request, session);
    }
}
```

### In a Spring REST Controller

```java
@RestController
public class AiController {

    @Autowired
    private SpringAiStreamingAdapter springAiAdapter;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private AtmosphereFramework framework;

    @PostMapping("/api/ask")
    public void ask(@RequestBody String question, HttpServletResponse response) {
        var resource = AtmosphereResourceFactory.getDefault()
            .find(framework, response);

        var session = StreamingSessions.start(resource);
        springAiAdapter.stream(chatClient, question, session, spec -> {
            spec.system("You are a helpful assistant");
            spec.advisors(myRagAdvisor);
        });
    }
}
```

## Writing a Custom AiSupport

If you use an AI framework that Atmosphere does not have a built-in adapter for, you can write your own `AiSupport`:

```java
public class MyCustomAiSupport implements AiSupport {

    private MyLlmClient client;

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.example.mylm.MyLlmClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;  // higher than built-in (0)
    }

    @Override
    public void configure(LlmSettings settings) {
        client = new MyLlmClient(settings.apiKey(), settings.model());
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        client.streamChat(request.message(), new MyLlmCallback() {
            @Override
            public void onToken(String token) {
                session.send(token);
            }

            @Override
            public void onDone() {
                session.complete();
            }

            @Override
            public void onError(Throwable t) {
                session.error(t.getMessage());
            }
        });
    }
}
```

Register it via `ServiceLoader`:

```
# META-INF/services/org.atmosphere.ai.spi.AiSupport
com.example.MyCustomAiSupport
```

## Multiple Adapters on the Classpath

Having multiple adapter JARs on the classpath is valid. The highest-priority adapter that reports `isAvailable() == true` wins. This can be useful for:

- **Gradual migration:** Add the new adapter first, verify it works, then remove the old one.
- **Testing:** Use the built-in adapter in tests (no API key needed with Ollama) and Spring AI in production.

If you need to **use multiple backends simultaneously** (e.g., route simple queries to a fast model and complex queries to a powerful one), use `RoutingLlmClient` covered in [Chapter 12](/docs/tutorial/12-ai-filters/).

## Sample Applications

Each adapter has a corresponding sample:

| Sample | Adapter | Description |
|--------|---------|-------------|
| `spring-boot-ai-chat` | Built-in | Gemini/OpenAI/Ollama with no extra dependencies |
| `spring-boot-spring-ai-chat` | Spring AI | `ChatClient` with Spring AI advisors |
| `spring-boot-langchain4j-chat` | LangChain4j | `StreamingChatLanguageModel` with LangChain4j |
| `spring-boot-adk-chat` | Google ADK | ADK `Runner` with multi-agent support |
| `spring-boot-embabel-chat` | Embabel | `AgentPlatform` with step progress tracking |

Run any sample:

```bash
cd samples/spring-boot-ai-chat
export LLM_API_KEY=your-api-key
../../mvnw spring-boot:run
```

## What is Next

Now that you understand how adapters work, [Chapter 12](/docs/tutorial/12-ai-filters/) covers the middleware layer that sits between your `@Prompt` method and the LLM: filters for PII redaction, content safety, cost metering, multi-model routing, and fan-out streaming.

## Key Takeaways

- `AiSupport` is a `ServiceLoader`-discovered SPI. The highest-priority available adapter wins.
- The built-in `OpenAiCompatibleClient` (priority 0) is always available and works with Gemini, OpenAI, and Ollama.
- Framework adapters (Spring AI, LangChain4j, ADK, Embabel) have priority 100 and auto-detect via classpath scanning.
- Swap backends by changing a Maven dependency -- no code changes to `@AiEndpoint`, `@Prompt`, or `@AiTool`.
- You can bypass `@AiEndpoint` and use adapters directly for full control.
- Writing a custom `AiSupport` requires four methods: `isAvailable()`, `priority()`, `configure()`, and `stream()`.
- The `AiSupport` SPI mirrors `AsyncSupport` -- same design pattern applied to the AI layer.
