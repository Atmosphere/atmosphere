# Atmosphere Kotlin DSL

Builder APIs and coroutine extensions for Atmosphere: a **transport DSL**
(`atmosphere { }`) for endpoints, and an **agent DSL** (`registerAgent { }`)
for AI agents.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>${project.version}</version>
</dependency>
```

## DSL Builder

```kotlin
import org.atmosphere.kotlin.atmosphere

val handler = atmosphere {
    onConnect { resource ->
        println("${resource.uuid()} connected via ${resource.transport()}")
    }
    onMessage { resource, message ->
        resource.broadcaster.broadcast(message)
    }
    onDisconnect { resource ->
        println("${resource.uuid()} left")
    }
}

framework.addAtmosphereHandler("/chat", handler)
```

## Coroutine Extensions

```kotlin
broadcaster.broadcastSuspend("Hello!")     // suspends instead of blocking
resource.writeSuspend("Direct message")    // suspends instead of blocking
```

## Agent DSL

Declares an agent over the real AI layer — no annotations, no second stack.

```kotlin
import org.atmosphere.kotlin.ai.registerAgent

val assistant = framework.registerAgent("support") {
    systemPrompt = "You are a concise support assistant."
    model = "gpt-4o-mini"          // optional; falls back to the atmosphere.ai model
    maxHistory = 20                // conversation memory window (memory = false to disable)

    tool("order_status", "Look up the status of an order") {
        param("orderId", "The order identifier")
        execute { args -> lookup(args["orderId"] as String) }
    }
}

// Suspending call — collects the full answer
val answer = assistant.ask("user-42", "where is order 7?")

// Or consume the runtime's deltas as they arrive
assistant.stream("user-42", "summarise the incident").collect { delta ->
    resource.writeSuspend(delta)
}
```

`agent("name") { ... }` returns an inert `AgentSpec` if you want to declare and
register in separate steps: `framework.registerAgent(spec)`.

### What registration actually does

The DSL is a front-end onto the framework's own machinery, not a parallel
implementation. `registerAgent` builds exactly what the `@Agent` /
`@AiEndpoint` annotation processors build:

| Step | Shared with the annotation path |
|---|---|
| Runtime | `AgentRuntimeResolver.resolveAll()` — highest-priority available runtime, every backend `configure`d first, eager-`configure()` failures logged and tolerated |
| Tools | lambdas become `ToolDefinition`s in a `DefaultToolRegistry` — the registry `@AiTool` scanning fills |
| Memory | `InMemoryConversationMemory` honoring `org.atmosphere.ai.compaction` |
| Governance | every installed `GovernancePolicy` wrapped as a `PolicyAsGuardrail` on the streaming path and passed as a policy to the pipeline |
| HTTP/WebSocket endpoint | an `AiEndpointHandler` registered at `/atmosphere/agent/{name}` with the framework's default managed-service interceptors |
| Programmatic dispatch | the same `AiPipeline` object the A2A / AG-UI / channel surfaces run on |

So a DSL-declared agent is reachable by the browser client exactly like an
annotated one, and `ask` / `stream` traverse the same governance, memory, and
metrics stack as an HTTP caller.

### Dependency

The agent DSL needs `atmosphere-ai`, which this module declares **optional** —
apps using only the transport DSL never load AI types. Add it explicitly:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>${project.version}</version>
</dependency>
```

With no API key configured the resolver selects the framework's offline
`DemoAgentRuntime`; add a provider module (`atmosphere-langchain4j`,
`atmosphere-spring-ai`, `atmosphere-anthropic`, …) and export its key to get
real model output — the DSL code does not change.

Runnable proof: [`samples/kotlin-dsl-chat`](../../samples/kotlin-dsl-chat).

## Full Documentation

See <https://atmosphere.github.io/docs/clients/kotlin/> for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- Kotlin 2.1+
- kotlinx-coroutines 1.10+
