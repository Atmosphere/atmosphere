## Atmosphere 4.0: Modern Real-Time Java with JDK 21+

> ⚠️ **EXPERIMENTAL BUILD** - Atmosphere 4.0 is a complete rewrite leveraging JDK 21+ features including Virtual Threads, Structured Concurrency, and modern APIs. This is a preview release for early adopters.

Atmosphere 4.0 brings cutting-edge Java platform features to real-time web applications. Built on JDK 21-25 with Virtual Threads at its core, offering WebSocket with intelligent fallback to SSE and Long-Polling.

[![Atmosphere 4.x](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml/badge.svg?branch=atmosphere-4.x)](https://github.com/Atmosphere/atmosphere/actions/workflows/atmosphere-4x-ci.yml)

### What's New in 4.0

- **Virtual Threads** - Every connection runs on a virtual thread, enabling massive scalability
- **Structured Concurrency** - Reliable broadcast operations with automatic cancellation
- **JDK 21-25 Ready** - Preview & incubator features enabled
- **Monorepo** - Framework, samples, and TypeScript client in one place
- **Jakarta EE 10+** - Servlet 6.0, WebSocket 2.1, CDI 4.0
- **TypeScript Client** - Modern atmosphere.js 5.0 with type safety

### Choose Your Stack

| Stack | Artifact | Min version |
|-------|----------|-------------|
| **Spring Boot** | `atmosphere-spring-boot-starter` | Spring Boot 4.0.2+ |
| **Quarkus** | `atmosphere-quarkus-extension` | Quarkus 3.21+ |
| **Any Servlet container** | `atmosphere-runtime` | Servlet 3.0+ |

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

---

### Spring Boot Applications

The `atmosphere-spring-boot-starter` provides zero-configuration integration with **Spring Boot 4.0.2+**, including auto-configured servlet, Spring DI bridge, and optional Actuator health indicator.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>3.1.0</version>
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

### Quarkus Applications

The `atmosphere-quarkus-extension` brings Atmosphere to **Quarkus 3.21+** with build-time annotation scanning via Jandex, Arc CDI integration, and native image support.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>3.1.0</version>
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

### Standalone / Servlet Container

For Tomcat, Jetty, Undertow, or any Servlet 3.0+ container:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>3.1.0</version>
</dependency>
```

### Atmosphere 2.7.x (Java 8+)

[![Atmosphere 2.7.x](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml/badge.svg?branch=atmosphere-2.7.x)](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml)

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>2.7.15</version>
</dependency>
```

Modules: runtime, jersey, spring, kafka, guice, redis, hazelcast, jms, rabbitmq, jgroups, and more on [Maven Central](http://search.maven.org/#search|ga|1|atmosphere).

---

### Requirements

| Branch | Java | Spring Boot | Quarkus |
|--------|------|-------------|---------|
| 3.1.x (main) | 17+ | 4.0.2+ | 3.21+ |
| 2.7.x | 8 -- 25 | -- | -- |

### Documentation

- [Samples](https://github.com/Atmosphere/atmosphere-samples/) -- Spring Boot chat, Quarkus chat, and many more
- [Wiki & Tutorials](https://github.com/Atmosphere/atmosphere/wiki)
- [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions)
- [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/)
- [atmosphere.js API](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API)
- [DeepWiki](https://deepwiki.com/Atmosphere/atmosphere) -- AI-powered code exploration

### Client Libraries

- **JavaScript**: [atmosphere.js](https://github.com/Atmosphere/atmosphere-javascript) (included in samples)
- **Java/Scala/Android**: [wAsync](https://github.com/Atmosphere/wasync)

### Commercial Support

Available via [Async-IO.org](http://async-io.org)

---

### Legacy Integrations

| Stack | Project |
|-------|---------|
| **Netty** | [Nettosphere](http://atmosphere.github.io/nettosphere/) |
| **Play** | [atmosphere-play](http://atmosphere.github.io/atmosphere-play/) |
| **Vert.x** | [atmosphere-vertx](https://github.com/Atmosphere/atmosphere-vertx) |

Extensions: [Apache Kafka](https://github.com/Atmosphere/atmosphere-extensions/tree/master/kafka/modules), [Hazelcast](https://github.com/Atmosphere/atmosphere-extensions/tree/master/hazelcast/modules), [RabbitMQ](https://github.com/Atmosphere/atmosphere-extensions/tree/master/rabbitmq/modules), [Redis](https://github.com/Atmosphere/atmosphere-extensions/tree/master/redis/modules) and [more](https://github.com/Atmosphere/atmosphere-extensions/tree/extensions-2.4.x).

---

@Copyright 2008-2026 [Async-IO.org](http://async-io.org)
