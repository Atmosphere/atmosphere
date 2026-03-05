---
title: "@AiTool — Framework-Agnostic Tool Calling"
description: "Declare tools once with @AiTool and @Param, and they work with Spring AI, LangChain4j, Google ADK, or the built-in client"
---

In [Chapter 9](/docs/tutorial/09-ai-endpoint/) you built an AI endpoint that streams LLM responses to the browser. But LLMs can do more than generate text -- they can decide to **call tools**: functions you define that the model can invoke when it needs external data or wants to take an action. This chapter covers `@AiTool`, Atmosphere's framework-agnostic annotation for tool calling.

## The Problem with Framework-Specific Tools

Every AI framework has its own way of defining tools:

- **LangChain4j** uses `@Tool` and `@P` annotations with `ToolSpecification`.
- **Spring AI** uses `FunctionCallback` and JSON Schema.
- **Google ADK** uses `BaseTool` subclasses.

If you define your tools with one framework's API and later switch to another, you rewrite all your tool code. Atmosphere solves this with `@AiTool` -- a single annotation that is **bridged automatically** to whatever backend is on the classpath.

## Defining Tools with @AiTool

A tool is a plain Java method annotated with `@AiTool`. Parameters are annotated with `@Param` to provide metadata that the LLM uses to understand when and how to call the tool.

```java
public class AssistantTools {

    @AiTool(name = "get_weather",
            description = "Returns current weather conditions for a city")
    public String getWeather(
            @Param(value = "city", description = "City name, e.g. 'San Francisco'")
            String city) {
        return weatherApi.getCurrent(city).toString();
    }

    @AiTool(name = "search_docs",
            description = "Searches the documentation for a query and returns matching excerpts")
    public String searchDocs(
            @Param(value = "query", description = "Search query")
            String query,
            @Param(value = "max_results", description = "Maximum results to return (1-10)")
            int maxResults) {
        return docSearch.search(query, maxResults)
                .stream()
                .map(doc -> "## " + doc.title() + "\n" + doc.excerpt())
                .collect(Collectors.joining("\n\n"));
    }
}
```

### @AiTool Attributes

| Attribute | Type | Description |
|-----------|------|-------------|
| `name` | `String` | The tool name the LLM sees. Use `snake_case` by convention. |
| `description` | `String` | Plain English description of what the tool does. The LLM uses this to decide when to call it. |

### @Param Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | (required) | Parameter name the LLM sees. |
| `description` | `String` | `""` | Description of the parameter, helping the LLM provide correct values. |
| `required` | `boolean` | `true` | Whether the parameter is required. |

### Supported Parameter Types

`@AiTool` methods support these parameter types:

| Type | JSON Schema Type | Notes |
|------|-----------------|-------|
| `String` | `string` | Most common. |
| `int` / `Integer` | `integer` | |
| `long` / `Long` | `integer` | |
| `double` / `Double` | `number` | |
| `boolean` / `Boolean` | `boolean` | |
| `List<String>` | `array` of `string` | |
| `Map<String, Object>` | `object` | For complex structured input. |
| `enum` types | `string` with `enum` values | Enum constants become the allowed values. |

### Return Type

The return type should be `String`. The framework converts it to text that is fed back to the LLM as the tool result. If you return a non-String type, `toString()` is called.

## Wiring Tools to an Endpoint

Connect tools to your endpoint with the `tools` attribute:

```java
@AiEndpoint(
    path = "/ai/chat",
    systemPrompt = "You are a helpful assistant. Use tools when you need external data.",
    conversationMemory = true,
    tools = AssistantTools.class
)
public class ChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);  // tools are automatically available to the LLM
    }
}
```

You can wire multiple tool classes:

```java
@AiEndpoint(
    path = "/ai/chat",
    systemPrompt = "You are a helpful assistant",
    tools = {WeatherTools.class, CalendarTools.class, SearchTools.class}
)
public class ChatBot {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### No Code Changes to @Prompt

Notice that the `@Prompt` method does not change -- it still calls `session.stream(message)`. The tool calling loop is handled internally:

1. The LLM receives the prompt plus the tool definitions.
2. If the LLM decides to call a tool, the framework intercepts the tool call request.
3. The framework executes the `@AiTool` method with the arguments the LLM provided.
4. The tool result is sent back to the LLM.
5. The LLM may call more tools or produce a final text response.
6. The final response is streamed to the client via `StreamingSession`.

The user sees the final answer -- tool calls happen transparently.

## DefaultToolRegistry -- How Tools Are Discovered

At startup, the framework scans classes listed in `@AiEndpoint(tools = ...)` and builds a global `DefaultToolRegistry`.

The registry is a map from tool name to a `ToolDefinition` record containing:

- The method reference.
- The tool name and description.
- The parameter schema (names, types, descriptions, required flags).
- The declaring class instance (created once, reused).

### Startup Scan

```java
// Conceptually, what the framework does at startup:
for (var toolClass : endpoint.tools()) {
    for (var method : toolClass.getDeclaredMethods()) {
        var annotation = method.getAnnotation(AiTool.class);
        if (annotation != null) {
            registry.register(new ToolDefinition(
                annotation.name(),
                annotation.description(),
                extractParams(method),
                method,
                toolClass
            ));
        }
    }
}
```

### Manual Registration

You can also register tools programmatically:

```java
var registry = ToolRegistry.get(framework);
registry.register("custom_tool", "Does something custom",
    Map.of("input", new ParamSchema("string", "The input", true)),
    args -> myService.process((String) args.get("input")));
```

## The Bridge Layer -- How @AiTool Becomes Native

The key innovation is the **tool bridge**. When `session.stream()` prepares the `AiRequest`, it includes the registered tools. The active `AiSupport` implementation uses a bridge to convert `@AiTool` definitions to its native format.

### The Flow

1. `@AiTool` annotations are parsed at startup and stored in `DefaultToolRegistry`.
2. When `session.stream()` is called, tools are included in the `AiRequest`.
3. The active `AiSupport` backend calls its bridge to convert `ToolDefinition` to native format.
4. The LLM receives the prompt with tool definitions and may decide to call one.
5. `ToolExecutor.execute()` invokes the original `@AiTool` method.
6. The result string is fed back to the LLM.
7. The LLM produces a final response, streamed to the client.

### Bridge Details

| Backend | Bridge Class | Native Format |
|---------|-------------|---------------|
| LangChain4j | `LangChain4jToolBridge` | `ToolSpecification` |
| Spring AI | `SpringAiToolBridge` | `ToolCallback` |
| Google ADK | `AdkToolBridge` | `BaseTool` |
| Built-in | `BuiltInToolBridge` | OpenAI function spec |

### What "Bridge" Means in Practice

When you use LangChain4j as your backend, the bridge does something equivalent to:

```java
// What @AiTool(name = "get_weather", description = "...")
// with @Param(value = "city", description = "...") becomes:

ToolSpecification.builder()
    .name("get_weather")
    .description("Returns current weather conditions for a city")
    .addParameter("city", JsonSchemaProperty.STRING,
        JsonSchemaProperty.description("City name, e.g. 'San Francisco'"))
    .build();
```

When you switch to Spring AI, the same `@AiTool` becomes:

```java
FunctionCallback.builder()
    .name("get_weather")
    .description("Returns current weather conditions for a city")
    .inputType(GetWeatherRequest.class)  // generated from @Param metadata
    .function(args -> toolExecutor.execute("get_weather", args))
    .build();
```

You never write this bridging code -- it happens automatically.

## Practical Example: A Multi-Tool Assistant

Here is a complete example with multiple tool classes wired to a single endpoint.

### Tool Classes

```java
public class WeatherTools {

    private final WeatherService weatherService;

    public WeatherTools(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @AiTool(name = "get_current_weather",
            description = "Get current weather conditions for a location")
    public String getCurrentWeather(
            @Param(value = "city", description = "City name") String city,
            @Param(value = "unit", description = "'celsius' or 'fahrenheit'")
            String unit) {
        var weather = weatherService.getCurrent(city);
        double temp = "fahrenheit".equalsIgnoreCase(unit)
                ? weather.tempCelsius() * 9.0 / 5.0 + 32
                : weather.tempCelsius();
        return "%s: %.1f%s, %s".formatted(
                city, temp,
                "fahrenheit".equalsIgnoreCase(unit) ? "F" : "C",
                weather.condition());
    }

    @AiTool(name = "get_forecast",
            description = "Get weather forecast for the next N days")
    public String getForecast(
            @Param(value = "city", description = "City name") String city,
            @Param(value = "days", description = "Number of days (1-7)") int days) {
        return weatherService.getForecast(city, days).stream()
                .map(f -> "%s: %.1fC, %s".formatted(f.date(), f.tempCelsius(), f.condition()))
                .collect(Collectors.joining("\n"));
    }
}
```

```java
public class CalculatorTools {

    @AiTool(name = "calculate",
            description = "Evaluate a mathematical expression")
    public String calculate(
            @Param(value = "expression", description = "Math expression, e.g. '2 + 3 * 4'")
            String expression) {
        return String.valueOf(MathEvaluator.evaluate(expression));
    }

    @AiTool(name = "convert_units",
            description = "Convert between units of measurement")
    public String convertUnits(
            @Param(value = "value", description = "Numeric value to convert") double value,
            @Param(value = "from", description = "Source unit, e.g. 'km'") String from,
            @Param(value = "to", description = "Target unit, e.g. 'miles'") String to) {
        double result = UnitConverter.convert(value, from, to);
        return "%.4f %s = %.4f %s".formatted(value, from, result, to);
    }
}
```

### The Endpoint

```java
@AiEndpoint(
    path = "/ai/assistant",
    systemPrompt = """
        You are a helpful assistant with access to weather and calculator tools.
        Always use the tools when the user asks about weather or needs calculations.
        Present results in a clear, human-friendly format.
        """,
    conversationMemory = true,
    tools = {WeatherTools.class, CalculatorTools.class}
)
public class AssistantEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

### Example Conversation

Here is what happens when a user interacts with this endpoint:

**Turn 1:**

> **User:** What is the weather in Tokyo?
>
> *Framework: LLM calls `get_current_weather(city="Tokyo", unit="celsius")`*
> *Framework: Tool returns "Tokyo: 22.5C, Partly Cloudy"*
>
> **Assistant:** It is currently 22.5 degrees Celsius and partly cloudy in Tokyo.

**Turn 2:**

> **User:** Convert that to Fahrenheit.
>
> *Framework: LLM calls `convert_units(value=22.5, from="celsius", to="fahrenheit")`*
> *Framework: Tool returns "22.5000 celsius = 72.5000 fahrenheit"*
>
> **Assistant:** That is 72.5 degrees Fahrenheit.

**Turn 3:**

> **User:** What about the forecast for the next 3 days?
>
> *Framework: LLM calls `get_forecast(city="Tokyo", days=3)`, remembering the city from conversation memory*
> *Framework: Tool returns forecast data*
>
> **Assistant:** Here is the 3-day forecast for Tokyo...

Notice how conversation memory (Chapter 9) and tool calling work together -- the LLM remembers "Tokyo" from the first turn when the user says "the next 3 days" without repeating the city.

## Enum Parameters

Enums are useful when a tool parameter has a fixed set of valid values. The framework automatically includes the enum constants in the JSON Schema:

```java
public enum TemperatureUnit {
    CELSIUS, FAHRENHEIT, KELVIN
}

public class TemperatureTools {

    @AiTool(name = "convert_temperature",
            description = "Convert a temperature between units")
    public String convert(
            @Param(value = "value", description = "Temperature value") double value,
            @Param(value = "from", description = "Source unit") TemperatureUnit from,
            @Param(value = "to", description = "Target unit") TemperatureUnit to) {
        double celsius = switch (from) {
            case CELSIUS -> value;
            case FAHRENHEIT -> (value - 32) * 5.0 / 9.0;
            case KELVIN -> value - 273.15;
        };
        double result = switch (to) {
            case CELSIUS -> celsius;
            case FAHRENHEIT -> celsius * 9.0 / 5.0 + 32;
            case KELVIN -> celsius + 273.15;
        };
        return "%.2f %s = %.2f %s".formatted(value, from, result, to);
    }
}
```

The JSON Schema sent to the LLM includes:

```json
{
  "name": "convert_temperature",
  "parameters": {
    "properties": {
      "from": {
        "type": "string",
        "enum": ["CELSIUS", "FAHRENHEIT", "KELVIN"],
        "description": "Source unit"
      }
    }
  }
}
```

## Tool Error Handling

If a tool throws an exception, the framework catches it and feeds the error message back to the LLM as the tool result. The LLM can then decide how to respond:

```java
@AiTool(name = "query_database",
        description = "Run a read-only SQL query against the database")
public String queryDatabase(
        @Param(value = "sql", description = "SQL SELECT query") String sql) {
    if (!sql.trim().toUpperCase().startsWith("SELECT")) {
        throw new IllegalArgumentException("Only SELECT queries are allowed");
    }
    return jdbcTemplate.queryForList(sql).toString();
}
```

If the LLM sends a `DELETE` query, the tool throws, and the framework returns to the LLM:

```
Tool error: Only SELECT queries are allowed
```

The LLM typically responds with something like: "I can only run SELECT queries. Let me rephrase..."

## @AiTool vs Native Annotations

If you already have tools written with a framework-specific annotation, you do not need to rewrite them. Native annotations still work when you use that specific backend. `@AiTool` is for when you want **portability**.

| Feature | `@AiTool` (Atmosphere) | `@Tool` (LangChain4j) | `FunctionCallback` (Spring AI) |
|---------|------------------------|----------------------|-------------------------------|
| Portable across backends | Yes | No | No |
| Parameter metadata | `@Param` annotation | `@P` annotation | JSON Schema |
| Registration | `ToolRegistry` (global) | Per-service | Per-ChatClient |
| Requires specific backend | No | LangChain4j | Spring AI |
| Works with built-in client | Yes | No | No |

## Swapping the Backend

To swap the AI backend, change only the Maven dependency -- no tool code changes:

```xml
<!-- Built-in (default, no extra dependency) -->
<artifactId>atmosphere-ai</artifactId>

<!-- Spring AI backend -->
<artifactId>atmosphere-spring-ai</artifactId>

<!-- LangChain4j backend -->
<artifactId>atmosphere-langchain4j</artifactId>

<!-- Google ADK backend -->
<artifactId>atmosphere-adk</artifactId>

<!-- Embabel backend -->
<artifactId>atmosphere-embabel</artifactId>
```

Your `@AiTool` methods, your `@AiEndpoint`, your `@Prompt` handler -- none of them change. The bridge layer handles the conversion at runtime.

## Dependency Injection in Tool Classes

When using Spring Boot or Quarkus, tool classes are created as beans, so you can inject dependencies:

### Spring Boot

```java
@Component
public class DatabaseTools {

    private final JdbcTemplate jdbc;

    public DatabaseTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @AiTool(name = "count_orders",
            description = "Count orders by status")
    public String countOrders(
            @Param(value = "status", description = "Order status: pending, shipped, delivered")
            String status) {
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE status = ?",
            Integer.class, status);
        return "There are %d %s orders.".formatted(count, status);
    }
}
```

### Quarkus

```java
@ApplicationScoped
public class InventoryTools {

    @Inject
    InventoryService inventory;

    @AiTool(name = "check_stock",
            description = "Check stock level for a product")
    public String checkStock(
            @Param(value = "sku", description = "Product SKU") String sku) {
        var stock = inventory.getStock(sku);
        return "%s: %d units in stock".formatted(sku, stock.quantity());
    }
}
```

## Sample Application

The `spring-boot-ai-tools` sample demonstrates everything in this chapter:

```bash
cd samples/spring-boot-ai-tools
export LLM_API_KEY=your-api-key
../../mvnw spring-boot:run
```

The sample includes weather and calculator tools wired to an `@AiEndpoint`, with a React frontend that shows the streaming response as the LLM reasons through tool calls.

## What is Next

You have seen how `@AiTool` provides portable tool calling. But what happens inside `session.stream()` when it "auto-detects" the AI backend? How does the framework choose between Spring AI, LangChain4j, ADK, and the built-in client? [Chapter 11](/docs/tutorial/11-ai-adapters/) explains the `AiSupport` SPI and how each adapter works.

## Key Takeaways

- `@AiTool` and `@Param` define tools in a framework-agnostic way.
- Tools are scanned at startup and stored in the `DefaultToolRegistry`.
- The bridge layer converts `@AiTool` to native format (`ToolSpecification`, `ToolCallback`, `BaseTool`, or OpenAI function spec) automatically.
- Your `@Prompt` method does not change -- `session.stream()` handles the tool calling loop.
- Swap the AI backend by changing a single Maven dependency -- no tool code changes.
- Tool exceptions are caught and fed back to the LLM as error messages.
- Enum parameters automatically generate `enum` constraints in the JSON Schema.
