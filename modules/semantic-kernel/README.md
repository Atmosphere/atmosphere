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
| `AgentRuntime` | `ChatCompletionService` (streaming via Reactor `Flux`; a turn with tools via the non-streaming `Mono`) |
| `EmbeddingRuntime` | `TextEmbeddingGenerationService` (async via Reactor `Mono`) |

## Capabilities

See the [capability matrix](../ai/README.md#capability-matrix) in the parent `atmosphere-ai` README for the authoritative cross-runtime view.

SK-specific: TEXT_STREAMING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, CONVERSATION_MEMORY, TOKEN_USAGE, TOOL_CALLING, TOOL_APPROVAL, PER_REQUEST_RETRY. Tool calling rides `SemanticKernelToolBridge` — a direct `KernelFunction<String>` subclass (one per Atmosphere tool) whose overridden `invokeAsync` routes through `ToolExecutionHelper.executeWithApproval`, no annotation processor or bytecode synthesis required.

### A turn with tools does not stream

SK 1.5.0 runs its auto-invoke tool loop only on the non-streaming
`ChatCompletionService.getChatMessageContentsAsync`. Its streaming call refuses
auto-invoke (`SKException: ToolCallBehavior auto-invoke is not supported for
streaming chat message contents`) and drops the model's tool calls from its
frames, so a manual loop over the stream is not possible either. The runtime
therefore sends a turn that carries tools — or a per-request
`InvocationContext` whose `ToolCallBehavior` / `FunctionChoiceBehavior` has
auto-invoke on — through the non-streaming call:

- the tools run inside SK's loop through `ToolExecutionHelper.executeWithApproval`
  (`@RequiresApproval`, argument validation, `ToolStart` / `ToolResult` events);
  SK hands tool arguments over as text, and the bridge gives `integer`,
  `number` and `boolean` parameters their declared type;
- the answer reaches the client in one piece once the loop ends, with the last
  round's token usage;
- once a tool round ended the turn (an approval that ran out with the turn's
  time limit, a cancel), a `PreChatCompletion` kernel hook stops the loop
  before its next model call;
- a loop that runs out of SK's auto-invoke attempts with tool calls still
  pending ends the turn with an error, not an empty answer;
- a model that ends the turn with no text and no tool call completes it, as on
  the other runtimes, and the runtime logs a warning with the completion tokens
  spent and the tools offered.

A turn without tools streams as before.

### Tool names and schemas on the wire

SK names a function `<plugin>-<function>` (`atmosphere_tools-get_weather`) and
hard-codes the `-`. On Ollama with qwen2.5 a hyphenated tool name made the
model's tool call unparseable: the turn ended with no text and no tool call.
Two kernel hooks therefore send each tool under its own name (`get_weather`,
as the built-in runtime sends it) with the JSON schema
`ToolBridgeUtils.buildJsonSchemaString` builds (the one the Spring AI bridge
sends: enum values, array items, object properties), and give the model's tool calls their qualified name back before
SK reads them. A parameter type SK cannot resolve on its own (`number` has no
entry in SK's type table, `object` needs SK's optional schema generator) is
declared to SK as `double` / `string`; the schema the model sees is the shared
one.

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
`ChatCompletionService.getStreamingChatMessageContentsAsync(...)`, or to
`getChatMessageContentsAsync(...)` when it turns auto-invoke on (see above).
When absent, it builds the default — preserving prior behavior and keeping
SK's non-null `ToolCallBehavior` requirement satisfied.

## Known limitations

- **Sync boundary**: both `AgentRuntime.execute()` and `EmbeddingRuntime.embed()` call Reactor `.block()` inside the SPI. SK 1.5.0 transitively ships `reactor-core:3.4.41` which can pin carrier threads on virtual-thread runtimes. Atmosphere's Spring Boot starter overrides this to Reactor 3.7+ (VT-safe); standalone users without Spring Boot should force `reactor-core >= 3.6.0` via `dependencyManagement`.
- **Model selection**: `models()` returns the deployment name from the configured `OpenAIAsyncClient` when available, empty list otherwise.

## Requirements

- Java 21+
- `com.microsoft.semantic-kernel:semantickernel-api:1.5.0+`
- `com.microsoft.semantic-kernel:semantickernel-aiservices-openai:1.5.0+` (provided scope)
