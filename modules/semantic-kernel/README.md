# Atmosphere Semantic Kernel Adapter

`AgentRuntime` implementation backed by Microsoft Semantic Kernel for Java. When this JAR is on the classpath, `@AiEndpoint` can route prompts through SK's `ChatCompletionService` and stream results to browser clients via Atmosphere's real-time transport.

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

SK-specific: TEXT_STREAMING, STRUCTURED_OUTPUT, SYSTEM_PROMPT, CONVERSATION_MEMORY, TOKEN_USAGE. TOOL_CALLING and TOOL_APPROVAL are deferred until the SK tool bridge lands (SK's `@DefineKernelFunction` requires compile-time annotation processing that doesn't map cleanly to Atmosphere's `ToolDefinition` builder pattern).

## EmbeddingRuntime

`SemanticKernelEmbeddingRuntime` wraps `TextEmbeddingGenerationService` and is registered via `META-INF/services/org.atmosphere.ai.EmbeddingRuntime`. The SK embedding API is Reactor-based (`Mono<List<Embedding>>`); the adapter calls `.block()` at the synchronous `EmbeddingRuntime.embed()` boundary.

`Embedding.getVector()` returns `List<Float>` (not `float[]`); the adapter unwraps to the primitive array the Atmosphere SPI requires.

## Known limitations

- **Sync boundary**: both `AgentRuntime.execute()` and `EmbeddingRuntime.embed()` call Reactor `.block()` inside the SPI. SK 1.4.0 transitively ships `reactor-core:3.4.38` which can pin carrier threads on virtual-thread runtimes. Atmosphere's Spring Boot starter overrides this to Reactor 3.7+ (VT-safe); standalone users without Spring Boot should force `reactor-core >= 3.6.0` via `dependencyManagement`.
- **Tool calling deferred**: SK's native tool dispatch (`@DefineKernelFunction`) is not yet bridged. Tools declared via `@AiTool` are silently omitted when SK is the active runtime.
- **Model selection**: `models()` returns the deployment name from the configured `OpenAIAsyncClient` when available, empty list otherwise.

## Requirements

- Java 21+
- `com.microsoft.semantic-kernel:semantickernel-api:1.4.0+`
- `com.microsoft.semantic-kernel:semantickernel-aiservices-openai:1.4.0+` (provided scope)
