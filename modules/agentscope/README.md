# Atmosphere AgentScope Adapter

`AgentRuntime` implementation backed by [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)
(Tongyi Lab) `ReActAgent`. When this JAR is on the classpath, `@AiEndpoint`
can run AgentScope agents and stream their output to browser clients in
real-time over Atmosphere's transport.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-agentscope</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Or use the CLI to scaffold a working sample:

```bash
atmosphere new my-app --template ai-chat --runtime agentscope --force
```

## Quick Start

The Spring Boot autoconfig auto-builds an `OpenAIChatModel` and `ReActAgent`
when `llm.api-key` is set, so the default `spring-boot-ai-chat` template
runs unchanged:

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: https://api.openai.com/v1
  model: gpt-4o-mini
```

Override the system prompt with `atmosphere.agentscope.system-prompt`
(falls back to `llm.system-prompt`).

For full control, declare your own `ReActAgent` `@Bean` — the autoconfig
detects it via `@ConditionalOnMissingBean` and skips the default:

```java
@Bean
public ReActAgent reActAgent() {
    return ReActAgent.builder()
            .name("custom-agent")
            .sysPrompt("You are a precise research assistant.")
            .model(DashScopeChatModel.builder()
                    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                    .modelName("qwen-plus")
                    .build())
            .toolkit(myToolkit)
            .memory(myMemory)
            .build();
}
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AgentScopeAgentRuntime` | `AgentRuntime` SPI implementation (priority 100) |
| `AtmosphereAgentScopeAutoConfiguration` | Spring Boot auto-configuration |

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) for the
authoritative cross-runtime view. Honest declared set:

- **Text streaming** — bridges `Flux<Event> ReActAgent.stream(List<Msg>, StreamOptions)`
  through `StreamingSession.send()`. Each `Event.getMessage().getTextContent()`
  delta forwards as a chunk; the terminal `event.isLast()` event closes the
  session.
- **System prompt** — threaded as a `Msg` with `MsgRole.SYSTEM` at the head
  of the dispatched message list (in addition to whatever the agent's own
  builder-time `sysPrompt` was).
- **Structured output** — surfaced via Atmosphere's pipeline-level
  `StructuredOutputCapturingSession` (auto-enabled for every
  `SYSTEM_PROMPT`-capable runtime).
- **Conversation memory** — `assembleMessages` threads `context.history()`
  into the `List<Msg>` the agent receives.
- **Token usage** — captured from `Msg.getChatUsage()` on the terminal
  event and emitted via `session.usage()`.

## Streaming Bridge

AgentScope's `Flux<Event>` is forwarded to `StreamingSession`:

| AgentScope Event | Atmosphere session call |
|---|---|
| `event.getMessage().getTextContent()` (non-empty, non-final) | `session.send(text)` |
| `event.isLast()` with `Msg.getChatUsage()` | `session.usage(TokenUsage)` |
| Reactor `onComplete`                       | `session.complete()` |
| Reactor `onError(Throwable)`               | `session.error(t)` |

## Cancellation

`ExecutionHandle.cancel()` fires both `agent.interrupt()` (cooperative
cancel of the ReAct loop) and `Disposable.dispose()` (drops in-flight
emissions to the session). The `done` `CompletableFuture` resolves with
`CancellationException`, satisfying Correctness Invariant #2 (Terminal
Path Completeness).

## Capabilities NOT declared (and why)

Bridging these would mean adding a translation seam this first cut
doesn't yet ship. They will be declared honestly only when the bridge
exists:

- `TOOL_CALLING` / `TOOL_APPROVAL` — AgentScope's `Toolkit` and
  `ToolExecutor` need translation into Atmosphere's `ToolDefinition`
  surface, routed through `ToolExecutionHelper.executeWithApproval`.
- `VISION` / `AUDIO` / `MULTI_MODAL` — Atmosphere's `Content.Image` /
  `Content.Audio` parts need mapping to AgentScope `ContentBlock`
  subtypes.
- `PROMPT_CACHING` — AgentScope does not expose a portable cache-key
  primitive on the `Model` interface as of v1.0.12.
- `PER_REQUEST_RETRY` — would need wrapping `agent.stream(...)` in the
  outer retry path; AgentScope's own `ExecutionConfig` is the layer to
  bridge.

## Requirements

- Java 21+
- `atmosphere-ai` (transitive)
- AgentScope Java 1.0.12+
- Project Reactor (transitive via AgentScope)
- Spring Boot 4.0+ (uses `spring-boot-autoconfigure` 4.x autoconfig
  imports format)
