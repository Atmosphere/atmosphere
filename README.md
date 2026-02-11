## Atmosphere: Real-Time for Every Java Stack

Atmosphere is the production-proven framework for building real-time Java applications. WebSocket with automatic fallback to SSE, Long-Polling, HTTP Streaming, and JSONP -- write your handler once, Atmosphere negotiates the best transport.

**New in 3.1**: first-class [Spring Boot 4](#spring-boot-applications) and [Quarkus 3](#quarkus-applications) starters with zero-configuration setup, CDI/Spring DI bridges, and build-time annotation scanning.

[![Atmosphere 3.1.x](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml)

---

## Atmosphere 4.0 Experimental Branch

**EXPERIMENTAL**: Atmosphere 4.0 is an incremental modernization leveraging JDK 21+ features. This is bleeding-edge work and not recommended for production use.

### What's New in 4.0

- **JDK 21 baseline** with Virtual Threads for scalable concurrent connections
- **Structured Concurrency** for reliable broadcast operations
- **Scoped Values** replacing ThreadLocal for cleaner context propagation
- **Modern dependency stack**: Jakarta EE 10+, Servlet 6.1, WebSocket 2.2
- **Rewritten TypeScript client** (atmosphere.js 5.0) with modern async/await patterns
- **Integrated samples** in the main repository for easier testing

The 4.0 branch explores preview and incubator features to push Atmosphere's performance and developer experience forward. 

**[View Atmosphere 4.x README and Documentation →](https://github.com/Atmosphere/atmosphere/tree/atmosphere-4.x#readme)**

---

### Choose your stack

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
