# Atmosphere Kotlin DSL

Builder API and coroutine extensions for Atmosphere.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>4.0.11</version>
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

## Full Documentation

See [docs/kotlin.md](../../docs/kotlin.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- Kotlin 2.1+
- kotlinx-coroutines 1.10+
