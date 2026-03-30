# Spring AI Adapter

`atmosphere-spring-ai` bridges Spring AI's `ChatClient` to Atmosphere's `AgentRuntime` SPI.

## How It Works

`SpringAiAgentRuntime` extends `AbstractAgentRuntime<ChatClient>` and:

1. Builds a prompt using `ChatClient.prompt()` builder pattern
2. Sets system prompt via `.system()`, history via `.messages()`, user message via `.user()`
3. Bridges `@AiTool` methods via `SpringAiToolBridge` as Spring AI `ToolCallback` instances
4. Streams via `Flux<ChatResponse>` with `.blockLast()`

## Tool Calling

Spring AI handles tool calling automatically within its `ChatClient` pipeline. The framework detects `tool_calls` in responses, executes the registered callbacks, and re-submits. No manual loop needed.

## Capabilities

```
TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, SYSTEM_PROMPT
```

## Usage Metadata

Extracted from `response.getMetadata().getUsage()` in the `.doOnNext()` reactive callback:

- `usage.getPromptTokens()` -> `ai.tokens.input`
- `usage.getCompletionTokens()` -> `ai.tokens.output`
- `usage.getTotalTokens()` -> `ai.tokens.total`

## Streaming

The reactive pipeline:

```java
promptSpec.stream().chatResponse()
    .takeWhile(ignored -> !session.isClosed())
    .doOnNext(response -> session.send(text))
    .doOnComplete(session::complete)
    .doOnError(error -> session.error(error))
    .blockLast();
```

Large text chunks are split on whitespace for reliable console rendering.

## Spring Boot 4 Note

The parent POM's SLF4J 1.x and Logback 1.2.x must be overridden with SLF4J 2.x / Logback 1.5.x for Spring Boot 4 compatibility. This is handled in the module's `pom.xml`.

## Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
</dependency>
```
