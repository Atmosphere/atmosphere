# Atmosphere Semantic Kernel Adapter

`AgentRuntime` implementation backed by Microsoft Semantic Kernel for Java. When this JAR is on the classpath, `@AiEndpoint` can route prompts through SK's `ChatCompletionService` and stream results to browser clients via Atmosphere's real-time transport.

Use this adapter when Microsoft Semantic Kernel is already your plugin, function, and connector layer. Atmosphere keeps Semantic Kernel in charge of those native abstractions and adds the service layer around them — real-time client transports (WebSocket, SSE, long-polling, gRPC), `@Agent`/`@AiEndpoint` dispatch, governance and HITL approval, durable sessions and session-tape replay, and MCP/A2A/AG-UI exposure of the same agent. It runs on top of Semantic Kernel; it does not replace it.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-semantic-kernel</artifactId>
    <version>${project.version}</version>
</dependency>
```

## What it wraps

| Atmosphere SPI | SK native type |
|---------------|----------------|
| `AgentRuntime` | `ChatCompletionService` (streaming via Reactor `Flux`) |
| `EmbeddingRuntime` | `TextEmbeddingGenerationService` (async via Reactor `Mono`) |

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) in the parent `atmosphere-ai` README for the authoritative cross-runtime view.

SK-specific: TEXT_STREAMING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, CONVERSATION_MEMORY, TOKEN_USAGE, TOOL_CALLING, TOOL_APPROVAL, PER_REQUEST_RETRY. Tool calling rides `SemanticKernelToolBridge` — a direct `KernelFunction<String>` subclass (one per Atmosphere tool) whose overridden `invokeAsync` routes through `ToolExecutionHelper.executeWithApproval`, no annotation processor or bytecode synthesis required.

## EmbeddingRuntime

`SemanticKernelEmbeddingRuntime` wraps `TextEmbeddingGenerationService` and is registered via `META-INF/services/org.atmosphere.ai.EmbeddingRuntime`. The SK embedding API is Reactor-based (`Mono<List<Embedding>>`); the adapter calls `.block()` at the synchronous `EmbeddingRuntime.embed()` boundary.

`Embedding.getVector()` returns `List<Float>` (not `float[]`); the adapter unwraps to the primitive array the Atmosphere SPI requires.

## Per-Request InvocationContext (`SemanticKernelInvocation`)

By default the runtime builds an `InvocationContext` that carries only
`ToolCallBehavior.allowAllKernelFunctions(hasTools)`. To unlock SK's
power features per request — `KernelHooks` for function-invoking
filters, `withMaxAutoInvokeAttempts(int)` to cap the auto-invoke loop,
custom `PromptExecutionSettings` (temperature, max tokens, stop
sequences) — attach a fully-built `InvocationContext` via
`SemanticKernelInvocation.attach`:

```java
var invocation = InvocationContext.builder()
        .withToolCallBehavior(ToolCallBehavior.allowAllKernelFunctions(true))
        .withPromptExecutionSettings(
                PromptExecutionSettings.builder()
                        .withTemperature(0.2)
                        .withMaxTokens(2_048)
                        .build())
        .build();

var ctx = SemanticKernelInvocation.attach(baseContext, invocation);
runtime.execute(ctx, session);
```

When attached, the runtime passes the user-supplied context verbatim to
`ChatCompletionService.getStreamingChatMessageContentsAsync(...)`. When
absent, it builds the default — preserving prior behavior and keeping
SK's non-null `ToolCallBehavior` requirement satisfied.

## Known limitations

- **Sync boundary**: both `AgentRuntime.execute()` and `EmbeddingRuntime.embed()` call Reactor `.block()` inside the SPI. SK 1.5.0 transitively ships `reactor-core:3.4.41` which can pin carrier threads on virtual-thread runtimes. Atmosphere's Spring Boot starter overrides this to Reactor 3.7+ (VT-safe); standalone users without Spring Boot should force `reactor-core >= 3.6.0` via `dependencyManagement`.
- **Model selection**: `models()` returns the deployment name from the configured `OpenAIAsyncClient` when available, empty list otherwise.

## Requirements

- Java 21+
- `com.microsoft.semantic-kernel:semantickernel-api:1.5.0+`
- `com.microsoft.semantic-kernel:semantickernel-aiservices-openai:1.5.0+` (provided scope)
