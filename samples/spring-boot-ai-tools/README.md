# Atmosphere @AiTool Pipeline

Framework-agnostic AI tool calling — tools declared with `@AiTool` annotations are
automatically bridged to whichever AI backend is active (Spring AI, LangChain4j, or Google ADK).

## What It Does

The assistant has four tools registered via Atmosphere's `@AiTool` annotation:

| Tool | Description |
|------|-------------|
| `get_current_time` | Returns the current date and time |
| `get_city_time` | Returns the time in a specific city (New York, London, Paris, Tokyo, Sydney) |
| `get_weather` | Returns a weather report for a city |
| `convert_temperature` | Converts between Celsius and Fahrenheit |

## Running

```bash
# Demo mode (no API key needed — simulated responses)
cd samples/spring-boot-ai-tools
../../mvnw spring-boot:run

# With a real LLM
LLM_API_KEY=your-gemini-key ../../mvnw spring-boot:run

# Custom model
LLM_MODEL=gpt-4o LLM_API_KEY=your-key LLM_BASE_URL=https://api.openai.com/v1 \
  ../../mvnw spring-boot:run
```

Open http://localhost:8090 in your browser.

## Try These Prompts

- `What time is it in Tokyo?` — triggers the `get_city_time` tool
- `What's the weather in Paris?` — triggers the `get_weather` tool
- `Convert 100°F to Celsius` — triggers the `convert_temperature` tool
- `What tools do you have?` — lists available `@AiTool` methods

## Key Code

| File | Purpose |
|------|---------|
| `AiToolsChat.java` | `@AiEndpoint` with `tools = AssistantTools.class` |
| `AssistantTools.java` | `@AiTool`-annotated methods (portable across backends!) |
| `LlmConfig.java` | Configures LLM client as a Spring bean |
| `DemoResponseProducer.java` | Fallback when no API key is set |

## Architecture

```
Browser ──WebSocket──> Atmosphere ──> LangChain4jToolBridge
                           |              |
                           |         +----+----+
                           |         | @AiTool |
                           |         | methods |
                           |         +---------+
                           |
                     <-----+ streamed texts
```

## Key Difference vs LangChain4j Tools Sample

| This Sample | LangChain4j Tools Sample |
|------------|-------------------------|
| `@AiTool` (Atmosphere-native) | `@Tool` (LangChain4j-native) |
| Works with any AI backend | Locked to LangChain4j |
| Bridged via `ToolBridge` layer | Direct LangChain4j integration |

## Swapping the AI Backend

To use Spring AI instead of LangChain4j, just change the Maven dependency:

```xml
<!-- Replace this -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
</dependency>

<!-- With this -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
</dependency>
```

No tool code changes needed — `AssistantTools.java` works unchanged.
