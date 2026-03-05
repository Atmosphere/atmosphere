---
title: "Kotlin DSL"
description: "Builder API and coroutine extensions"
---

# Kotlin DSL

Builder API and coroutine extensions for Atmosphere.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

## DSL Builder

Create `AtmosphereHandler` instances with a type-safe DSL:

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
    onTimeout { resource ->
        println("${resource.uuid()} timed out")
    }
    onResume { resource ->
        println("${resource.uuid()} resumed")
    }
}

framework.addAtmosphereHandler("/chat", handler)
```

### Available callbacks

| Callback | Description |
|----------|-------------|
| `onConnect` | Called when a client connects |
| `onMessage` | Called when a message is received |
| `onDisconnect` | Called when a client disconnects |
| `onTimeout` | Called when a connection times out |
| `onResume` | Called when a suspended connection resumes |

## Coroutine Extensions

Suspending versions of blocking Atmosphere methods:

```kotlin
// Suspend instead of blocking on broadcast
broadcaster.broadcastSuspend("Hello!")

// Broadcast to a specific resource
broadcaster.broadcastSuspend("Private message", resource)

// Suspend instead of blocking on write
resource.writeSuspend("Direct message")
resource.writeSuspend(byteArrayOf(0x01, 0x02))
```

These extensions use `kotlinx.coroutines` to bridge Atmosphere's `Future`-based API into structured concurrency.

## Samples

- [Spring Boot Chat](../samples/spring-boot-chat/) -- can be used with Kotlin DSL

## See Also

- [Core Runtime](core.md) -- `AtmosphereHandler`, `Broadcaster`, `AtmosphereResource`
