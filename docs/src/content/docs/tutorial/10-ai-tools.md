---
title: "@AiTool -- Framework-Agnostic Tool Calling"
description: "Declare tools once with @AiTool and @Param, and they work with Spring AI, LangChain4j, Google ADK, or the built-in client"
sidebar:
  order: 10
---

In [Chapter 9](/docs/tutorial/09-ai-endpoint/) you built an AI endpoint that streams LLM responses to the browser. But LLMs can do more than generate text -- they can decide to **call tools**: functions you define that the model can invoke when it needs external data or wants to take an action. This chapter covers `@AiTool`, Atmosphere's framework-agnostic annotation for tool calling.

## The Problem with Framework-Specific Tools

Every AI framework has its own way of defining tools:

- **LangChain4j** uses `@Tool` on methods with `@P` for parameters
- **Spring AI** uses `ToolCallback` and `ToolDefinition` interfaces
- **Google ADK** uses `BaseTool` classes

If you define tools with one framework's annotations, switching to another requires rewriting every tool. Atmosphere solves this with `@AiTool` -- you define tools once, and the framework bridges them to whatever backend you are using.

## @AiTool Annotation

`@AiTool` marks a method as an AI-callable tool. It has two required attributes:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiTool {

    /** Tool name as exposed to the AI model. Convention: snake_case. */
    String name();

    /** Human-readable description of what the tool does. */
    String description();
}
```

| Attribute | Type | Description |
|-----------|------|-------------|
| `name` | `String` | Unique tool name (snake_case convention, e.g., `"get_weather"`) |
| `description` | `String` | Human-readable description sent to the model to help it decide when to call the tool |

## @Param Annotation

`@Param` annotates parameters of an `@AiTool`-annotated method to provide metadata for the AI model's tool schema:

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {

    /** Parameter name as exposed to the AI model. */
    String value();

    /** Human-readable description of this parameter. */
    String description() default "";

    /** Whether this parameter is required. Defaults to true. */
    boolean required() default true;
}
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | (required) | Parameter name as exposed to the model |
| `description` | `String` | `""` | Human-readable description |
| `required` | `boolean` | `true` | Whether the model must provide this parameter |

## Complete Example: AssistantTools

This is the `AssistantTools` class from the `spring-boot-ai-tools` sample:

```java
public class AssistantTools {

    @AiTool(name = "get_current_time",
            description = "Returns the current date and time in the server's timezone")
    public String getCurrentTime() {
        return ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }

    @AiTool(name = "get_city_time",
            description = "Returns the current time in a specific city")
    public String getCityTime(
            @Param(value = "city",
                   description = "City name (e.g., Tokyo, London, Paris, New York, Sydney)")
            String city) {
        var zone = switch (city.toLowerCase()) {
            case "tokyo" -> "Asia/Tokyo";
            case "london" -> "Europe/London";
            case "paris" -> "Europe/Paris";
            case "sydney" -> "Australia/Sydney";
            case "new york", "nyc" -> "America/New_York";
            case "los angeles", "la" -> "America/Los_Angeles";
            default -> "UTC";
        };
        return city + ": " + ZonedDateTime.now(ZoneId.of(zone))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss (z)"));
    }

    @AiTool(name = "get_weather",
            description = "Returns a weather report for a city with temperature and conditions")
    public String getWeather(
            @Param(value = "city", description = "City name to get weather for")
            String city) {
        return switch (city.toLowerCase()) {
            case "london" -> "London: Cloudy, 15C / 59F, 80% humidity";
            case "paris" -> "Paris: Partly cloudy, 20C / 68F, 65% humidity";
            case "tokyo" -> "Tokyo: Rainy, 22C / 72F, 90% humidity";
            default -> city + ": Clear, 22C / 72F, 50% humidity";
        };
    }

    @AiTool(name = "convert_temperature",
            description = "Converts a temperature between Celsius and Fahrenheit")
    public String convertTemperature(
            @Param(value = "value", description = "The temperature value to convert")
            double value,
            @Param(value = "from_unit",
                   description = "Source unit: 'C' for Celsius or 'F' for Fahrenheit")
            String fromUnit) {
        if ("C".equalsIgnoreCase(fromUnit) || "celsius".equalsIgnoreCase(fromUnit)) {
            double fahrenheit = value * 9.0 / 5.0 + 32;
            return String.format("%.1fC = %.1fF", value, fahrenheit);
        } else {
            double celsius = (value - 32) * 5.0 / 9.0;
            return String.format("%.1fF = %.1fC", value, celsius);
        }
    }
}
```

Key observations:

- **No framework imports** -- the class uses only `org.atmosphere.ai.annotation.*` and standard JDK types.
- **Return types are plain Java** -- `String`, not framework-specific result objects.
- **Parameters use `@Param`** -- providing name, description, and optionally `required = false`.
- **Type inference** -- the `double` parameter for `convert_temperature` is automatically mapped to JSON Schema type `"number"` by the `ToolParameter.jsonSchemaType()` method.

## Connecting Tools to an @AiEndpoint

Use the `tools` attribute on `@AiEndpoint`:

```java
@AiEndpoint(path = "/atmosphere/langchain4j-tools/{room}",
        systemPromptResource = "prompts/system-prompt.md",
        conversationMemory = true,
        maxHistoryMessages = 30,
        tools = AssistantTools.class,
        interceptors = CostMeteringInterceptor.class)
public class AiToolsChat {

    @PathParam("room")
    private String room;

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("[room={}] Client {} connected (peers: {})",
                room, resource.uuid(),
                resource.getBroadcaster().getAtmosphereResources().size());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("[room={}] Client {} disconnected",
                room, event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session,
                          AtmosphereResource resource) {
        logger.info("[room={}] Prompt from {}: {}", room, resource.uuid(), message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, room, "unknown");
            return;
        }

        session.stream(message);
    }
}
```

This endpoint demonstrates the full AI tool pipeline:

1. **`tools = AssistantTools.class`** -- tells the framework to scan `AssistantTools` for `@AiTool`-annotated methods and register them.
2. **`conversationMemory = true`** -- enables multi-turn context so the model can reference previous tool results.
3. **`maxHistoryMessages = 30`** -- retains up to 30 messages (15 turns) of conversation history.
4. **`interceptors = CostMeteringInterceptor.class`** -- adds cost estimation and routing metadata.
5. **`@PathParam("room")`** -- URI template variable for per-room AI sessions.

When `session.stream(message)` is called:

1. Tools from `AssistantTools` are attached to the `AiRequest`
2. The framework bridges them to the active backend's native tool format
3. The backend handles the tool call loop automatically
4. Tool results are fed back to the model for the final response

## Multiple Tool Classes

You can specify multiple tool provider classes:

```java
@AiEndpoint(path = "/chat",
    tools = {WeatherTools.class, CalendarTools.class, MathTools.class})
```

### Excluding Tools

When `tools` is empty (the default), all globally registered tools are available. Use `excludeTools` to selectively remove some:

```java
@AiEndpoint(path = "/public-chat",
    excludeTools = {AdminTools.class})
```

## ToolRegistry

The `ToolRegistry` is the global registry where tool definitions are stored. Tools are registered at startup (via `@AiTool` scanning or manual registration) and selected per-endpoint.

```java
public interface ToolRegistry {

    void register(ToolDefinition tool);

    void register(Object toolProvider);

    Optional<ToolDefinition> getTool(String name);

    Collection<ToolDefinition> getTools(Collection<String> names);

    Collection<ToolDefinition> allTools();

    boolean unregister(String name);

    ToolResult execute(String toolName, Map<String, Object> arguments);
}
```

| Method | Description |
|--------|-------------|
| `register(ToolDefinition)` | Register a single tool definition |
| `register(Object)` | Scan an object for `@AiTool`-annotated methods and register all of them |
| `getTool(name)` | Look up a tool by name |
| `getTools(names)` | Get tools matching the given names (silently skips unknown names) |
| `allTools()` | Get all registered tools |
| `unregister(name)` | Remove a tool by name |
| `execute(toolName, arguments)` | Execute a tool with the given arguments |

## ToolDefinition Record

Each registered tool is represented as a `ToolDefinition` record:

```java
public record ToolDefinition(
        String name,
        String description,
        List<ToolParameter> parameters,
        String returnType,
        ToolExecutor executor
) { }
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Unique tool name (must not be blank) |
| `description` | `String` | Description for the model (must not be blank) |
| `parameters` | `List<ToolParameter>` | Ordered parameter definitions |
| `returnType` | `String` | JSON Schema type of the return value (default: `"string"`) |
| `executor` | `ToolExecutor` | The function that executes the tool |

### ToolParameter Record

```java
public record ToolParameter(
        String name,
        String description,
        String type,       // JSON Schema type: string, integer, number, boolean, object, array
        boolean required
) { }
```

The `ToolParameter.jsonSchemaType(Class<?>)` static method maps Java types to JSON Schema types:

| Java Type | JSON Schema Type |
|-----------|-----------------|
| `String`, `CharSequence` | `"string"` |
| `int`, `Integer`, `long`, `Long` | `"integer"` |
| `float`, `Float`, `double`, `Double` | `"number"` |
| `boolean`, `Boolean` | `"boolean"` |
| Everything else | `"object"` |

### ToolExecutor Interface

```java
@FunctionalInterface
public interface ToolExecutor {
    Object execute(Map<String, Object> arguments) throws Exception;
}
```

The executor receives arguments as a `Map<String, Object>` keyed by parameter name and returns a result that will be serialized to JSON and sent back to the model.

### ToolResult Record

```java
public record ToolResult(String toolName, String result, boolean success, String error) {
    public static ToolResult success(String toolName, String result) { ... }
    public static ToolResult failure(String toolName, String error) { ... }
}
```

## Manual Tool Registration

You can register tools programmatically without annotations using the builder:

```java
var tool = ToolDefinition.builder("calculate_area", "Calculate the area of a rectangle")
        .parameter("width", "Width in meters", "number")
        .parameter("height", "Height in meters", "number")
        .returnType("number")
        .executor(args -> {
            double w = ((Number) args.get("width")).doubleValue();
            double h = ((Number) args.get("height")).doubleValue();
            return w * h;
        })
        .build();

toolRegistry.register(tool);
```

The builder API:

| Builder Method | Description |
|---------------|-------------|
| `builder(name, description)` | Create a new builder |
| `parameter(name, description, type)` | Add a required parameter |
| `parameter(name, description, type, required)` | Add a parameter with explicit required flag |
| `returnType(type)` | Set the return type (default: `"string"`) |
| `executor(ToolExecutor)` | Set the execution function (required) |
| `build()` | Build the `ToolDefinition` |

## How Tool Bridging Works

When `session.stream(message)` is called on an endpoint with tools, the framework:

1. Collects all `ToolDefinition` instances from the `ToolRegistry` that match the endpoint's `tools` attribute
2. Bridges them to the active backend's native format using a **ToolBridge**
3. The backend handles the tool call loop

Each AI backend has its own bridge:

### Spring AI (SpringAiToolBridge)

Converts Atmosphere `ToolDefinition` to Spring AI `ToolCallback`:

- Builds a JSON Schema from `ToolParameter` definitions
- Wraps the `ToolExecutor` in a `ToolCallback.call(String)` implementation
- Spring AI handles the tool call loop automatically -- it invokes the callback and feeds results back to the model

### LangChain4j (LangChain4jToolBridge)

Converts Atmosphere `ToolDefinition` to LangChain4j `ToolSpecification`:

- Maps `ToolParameter` types to LangChain4j JSON schema elements (`JsonStringSchema`, `JsonIntegerSchema`, `JsonNumberSchema`, `JsonBooleanSchema`)
- Unlike Spring AI, LangChain4j does not automatically execute tool callbacks -- when the model responds with `ToolExecutionRequest`s, the `LangChain4jToolBridge.executeToolCalls()` method runs the tools and returns `ToolExecutionResultMessage`s to feed back to the model
- This loop is handled by `ToolAwareStreamingResponseHandler`

### Google ADK (AdkToolBridge)

Converts Atmosphere `ToolDefinition` to ADK `BaseTool`:

- Wraps each tool as an ADK-compatible tool object
- ADK handles the tool call loop through its agent event system

## Conversation Memory with Tools

When tools are combined with `conversationMemory = true`, the conversation history includes tool calls and results. This lets the model:

1. Reference previous tool results ("What was the weather in London earlier?")
2. Build on previous answers ("Convert that temperature to Fahrenheit")
3. Use context from earlier turns to decide whether to call a tool again

The `AiConversationMemory` stores `ChatMessage` objects, and the sliding window at `maxHistoryMessages` ensures memory usage stays bounded.

```java
@AiEndpoint(path = "/chat",
    tools = AssistantTools.class,
    conversationMemory = true,
    maxHistoryMessages = 30)
```

With 30 messages and 4 tools, a typical conversation might look like:

```
User: "What time is it in Tokyo?"
→ Tool call: get_city_time(city="Tokyo")
→ Tool result: "Tokyo: 14:23:45 (JST)"
→ Assistant: "It's currently 2:23 PM in Tokyo (JST)."
User: "And the weather there?"
→ Tool call: get_weather(city="Tokyo")
→ Tool result: "Tokyo: Rainy, 22C / 72F, 90% humidity"
→ Assistant: "Tokyo is currently rainy at 22C..."
```

All of these messages are retained in the conversation memory, giving the model full context for follow-up questions.

## Samples

Two sample applications demonstrate tool calling:

- **`samples/spring-boot-ai-tools/`** -- uses the built-in LLM client with `@AiTool` methods (`AssistantTools`), conversation memory, and the `CostMeteringInterceptor`. Run with: `./mvnw spring-boot:run -pl samples/spring-boot-ai-tools`
- **`samples/spring-boot-langchain4j-tools/`** -- same tools, but powered by the LangChain4j adapter with `ToolAwareStreamingResponseHandler`. Run with: `./mvnw spring-boot:run -pl samples/spring-boot-langchain4j-tools`

Both samples share the same `AssistantTools` class, demonstrating that `@AiTool` definitions are adapter-independent.

## Summary

| Concept | Purpose |
|---------|---------|
| `@AiTool(name, description)` | Marks a method as an AI-callable tool |
| `@Param(value, description, required)` | Provides parameter metadata for the tool schema |
| `ToolRegistry` | Global registry for tool definitions |
| `ToolDefinition` | Record: name, description, parameters, returnType, executor |
| `ToolParameter` | Record: name, description, JSON Schema type, required |
| `ToolExecutor` | Functional interface that executes the tool |
| `ToolResult` | Record: toolName, result, success, error |
| `SpringAiToolBridge` | Bridges to Spring AI `ToolCallback` |
| `LangChain4jToolBridge` | Bridges to LangChain4j `ToolSpecification` |
| `AdkToolBridge` | Bridges to Google ADK `BaseTool` |
| `@AiEndpoint(tools={...})` | Selects which tool classes are available at this endpoint |
| `conversationMemory = true` | Enables multi-turn history including tool calls and results |

In the [next chapter](/docs/tutorial/11-ai-adapters/), you will learn how Atmosphere's AI adapters connect to Spring AI, LangChain4j, Google ADK, and the built-in OpenAI-compatible client.
