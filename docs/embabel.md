# Embabel Adapter

`atmosphere-embabel` bridges Embabel's GOAP-based `AgentPlatform` to Atmosphere's `AgentRuntime` SPI.

## Architecture Difference

Embabel does **not** extend `AbstractAgentRuntime`. Unlike client-based runtimes (LangChain4j, Spring AI, ADK), Embabel uses a service-based `AgentPlatform` with an `OutputChannel` callback pattern. The execution flow is:

1. `platform.runAgentFrom(prompt, options)` returns a `Process`
2. `AtmosphereOutputChannel` receives callbacks as the agent executes
3. Callbacks are bridged to `StreamingSession` events

## AtmosphereOutputChannel

Bridges Embabel's output events to Atmosphere:

| Embabel Event | Atmosphere Event |
|--------------|-----------------|
| `MessageOutputChannelEvent` | `AiEvent.TextDelta` |
| `ProgressOutputChannelEvent` | `AiEvent.AgentStep` |
| `LoggingOutputChannelEvent` | `session.progress()` |

## Structured Output

Embabel's `promptedTransformer` produces typed output on the blackboard, not through the `OutputChannel`. After the process completes, the runtime extracts the last result:

```kotlin
val result = process.blackboard.lastResult()
when (result) {
    is HasContent -> session.send(result.content)
    is String -> session.send(result)
    else -> session.send(result.toString())
}
```

## Capabilities

```
TEXT_STREAMING, STRUCTURED_OUTPUT, AGENT_ORCHESTRATION, SYSTEM_PROMPT
```

Note: Embabel does not support `TOOL_CALLING` (it uses its own goal-driven action system).

## Requirements

- **Spring Boot 3.5** (not Spring Boot 4 yet)
- Embabel's `AgentPlatform` must be configured as a Spring bean

## Error Handling

The runtime checks `session.isClosed()` before calling `complete()` to prevent double-completion if the session was closed during Embabel's execution.

## Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-embabel</artifactId>
</dependency>
```
