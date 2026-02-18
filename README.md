## Atmosphere 4.0: Modern Real-Time Java with JDK 21+

Atmosphere 4.0 brings cutting-edge Java platform features to real-time web applications. Built on JDK 21-25 with Virtual Threads at its core, offering WebSocket with intelligent fallback to SSE and Long-Polling.

[![Atmosphere CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml)
[![Atmosphere.js CI](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-js-ci.yml)
[![Samples CI](https://github.com/Atmosphere/atmosphere/actions/workflows/samples-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/samples-ci.yml)
[![Native Image CI](https://github.com/Atmosphere/atmosphere/actions/workflows/native-image-ci.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/native-image-ci.yml)

### What's New in 4.0

- **Virtual Threads** - Every connection runs on a virtual thread, enabling massive scalability
- **Structured Concurrency** - Reliable broadcast operations with automatic cancellation
- **Built-in Clustering** - Redis and Kafka broadcasters for multi-node deployments
- **JDK 21-25 Ready** - Preview & incubator features enabled
- **Monorepo** - Framework, samples, and TypeScript client in one place
- **Jakarta EE 10+** - Servlet 6.0, WebSocket 2.1, CDI 4.0
- **TypeScript Client** - Modern atmosphere.js 5.0 with type safety
- **Native Image** - GraalVM native builds for Spring Boot and Quarkus

### Choose Your Stack

| Stack | Artifact | Min version |
|-------|----------|-------------|
| **Spring Boot** | `atmosphere-spring-boot-starter` | Spring Boot 3.4+ |
| **Quarkus** | `atmosphere-quarkus-extension` | Quarkus 3.21+ |
| **Any Servlet container** | `atmosphere-runtime` | Servlet 6.0+ |

### Quick start

A real-time chat handler in 15 lines -- works identically on Spring Boot, Quarkus, or bare Servlet:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Inject private BroadcasterFactory factory;
    @Inject private AtmosphereResource r;

    @Ready
    public void onReady() {
        // client connected
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public Message onMessage(Message message) {
        return message; // broadcast to all
    }
}
```

Connect from the browser with [atmosphere.js](atmosphere.js/) 5.0:

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe({
    url: '/chat',
    transport: 'websocket',       // auto-falls back to SSE / long-polling
    reconnect: true,
}, {
    open:    ()   => console.log('Connected!'),
    message: (res) => console.log('Received:', res.responseBody),
    close:   ()   => console.log('Disconnected'),
});

// Send a message — broadcast to every connected client
subscription.push(JSON.stringify({ author: 'me', text: 'Hello!' }));
```

Install via npm:

```bash
npm install atmosphere.js
```

---

### Spring Boot Applications

The `atmosphere-spring-boot-starter` provides zero-configuration integration with **Spring Boot 3.4+**, including auto-configured servlet, Spring DI bridge, and optional Actuator health indicator.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

Configure via `application.yml`:

```yaml
atmosphere:
  packages: com.example.chat
```

<details>
<summary>All Spring Boot configuration properties</summary>

| Property | Default | Description |
|----------|---------|-------------|
| `servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `packages` | | Annotation scanning packages |
| `order` | `0` | Servlet load-on-startup order |
| `session-support` | `false` | Enable HttpSession support |
| `websocket-support` | | Enable/disable WebSocket |
| `heartbeat-interval-in-seconds` | | Server heartbeat frequency |
| `broadcaster-class` | | Custom Broadcaster FQCN |
| `broadcaster-cache-class` | | Custom BroadcasterCache FQCN |
| `init-params` | | Map of any `ApplicationConfig` key/value |

</details>

<details>
<summary>GraalVM Native Image (Spring Boot)</summary>

The starter includes Spring AOT runtime hints (`AtmosphereRuntimeHints`) that register all required reflection and resource metadata automatically. No manual configuration is needed -- just activate the `native` Maven profile:

```bash
# Build a native executable
./mvnw -Pnative package -pl samples/spring-boot-chat

# Run it
./samples/spring-boot-chat/target/atmosphere-spring-boot-chat
```

**Requirements:** GraalVM JDK 25+ (Spring Boot 4.0 / Spring Framework 7 requires GraalVM 25 as the native image baseline). The `native-maven-plugin` is inherited from `spring-boot-starter-parent`.

If your application uses custom `AtmosphereHandler`, `BroadcasterCache`, or encoder/decoder classes, add `@RegisterReflectionForBinding` or manual `RuntimeHintsRegistrar` entries for those classes.

</details>

### Quarkus Applications

The `atmosphere-quarkus-extension` brings Atmosphere to **Quarkus 3.21+** with build-time annotation scanning via Jandex, Arc CDI integration, and native image support.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

Configure via `application.properties`:

```properties
quarkus.atmosphere.packages=com.example.chat
```

<details>
<summary>All Quarkus configuration properties</summary>

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `quarkus.atmosphere.packages` | | Annotation scanning packages |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load-on-startup order |
| `quarkus.atmosphere.session-support` | `false` | Enable HttpSession support |
| `quarkus.atmosphere.broadcaster-class` | | Custom Broadcaster FQCN |
| `quarkus.atmosphere.broadcaster-cache-class` | | Custom BroadcasterCache FQCN |
| `quarkus.atmosphere.heartbeat-interval-in-seconds` | | Server heartbeat frequency |
| `quarkus.atmosphere.init-params` | | Map of any `ApplicationConfig` key/value |

</details>

<details>
<summary>GraalVM Native Image (Quarkus)</summary>

The Quarkus extension registers all reflection hints, ServiceLoader resources, and encoder/decoder classes at build time via `@BuildStep` processors. Native builds work out of the box:

```bash
# Build a native executable
./mvnw -Pnative package -pl samples/quarkus-chat

# Run it
./samples/quarkus-chat/target/atmosphere-quarkus-chat-*-runner
```

**Requirements:** GraalVM JDK 21+ (or Mandrel). Alternatively, use `-Dquarkus.native.container-build=true` to build inside a container without a local GraalVM installation.

Custom encoder/decoder classes annotated with Quarkus-scanned annotations are automatically registered for reflection. For classes loaded purely via `ApplicationConfig` init-params, add `@RegisterForReflection` to those classes.

</details>

### Standalone / Servlet Container

For Tomcat, Jetty, Undertow, or any Servlet 3.0+ container:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

### Clustering (Multi-Node)

Scale across multiple server instances with built-in Redis or Kafka broadcasters. Messages broadcast on any node are automatically delivered to clients connected to all other nodes.

<details>
<summary>Redis clustering</summary>

Add the Redis module — auto-detected on the classpath:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

Configure via init-param or `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis connection URL |
| `org.atmosphere.redis.password` | | Optional password |

Uses [Lettuce](https://lettuce.io/) 6.x for non-blocking Redis pub/sub.

</details>

<details>
<summary>Kafka clustering</summary>

Add the Kafka module — auto-detected on the classpath:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

Configure via init-param or `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka broker(s) |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Topic name prefix |
| `org.atmosphere.kafka.group.id` | auto-generated | Consumer group ID |

Uses the standard [Apache Kafka client](https://kafka.apache.org/) 3.x.

</details>

---

### Requirements

| Java | Spring Boot | Quarkus |
|------|-------------|---------|
| 21+  | 4.0.2+      | 3.21+   |

### Documentation

- [Samples](https://github.com/Atmosphere/atmosphere/tree/main/samples) — Spring Boot chat, Quarkus chat, embedded Jetty
- [Wiki & Tutorials](https://github.com/Atmosphere/atmosphere/wiki)
- [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions)
- [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/)
- [atmosphere.js API](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API)
- [DeepWiki](https://deepwiki.com/Atmosphere/atmosphere) — AI-powered code exploration

### Client Libraries

- **TypeScript/JavaScript**: [atmosphere.js](https://github.com/Atmosphere/atmosphere/tree/main/atmosphere.js) 5.0 (included in monorepo)
- **Java/Scala/Android**: [wAsync](https://github.com/Atmosphere/wasync)

### Commercial Support

Available via [Async-IO.org](http://async-io.org)

---

@Copyright 2008-2026 [Async-IO.org](http://async-io.org)
