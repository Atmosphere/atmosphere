# Quarkus Integration

A Quarkus extension that integrates Atmosphere with Quarkus 3.21+. Provides build-time annotation scanning via Jandex, Arc CDI integration, and GraalVM native image support.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.11</version>
</dependency>
```

The deployment artifact (`atmosphere-quarkus-extension-deployment`) is resolved automatically by Quarkus.

## Quick Start

### application.properties

```properties
quarkus.atmosphere.packages=com.example.chat
```

### Chat.java

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() { }

    @Disconnect
    public void onDisconnect() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

The extension auto-registers the Atmosphere servlet -- no `web.xml` or manual servlet registration needed. The same `@ManagedService` handler works across WAR, Spring Boot, and Quarkus -- only packaging and configuration differ.

## Configuration Properties

All properties are under the `quarkus.atmosphere.*` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.atmosphere.packages` | (none) | Comma-separated packages to scan |
| `quarkus.atmosphere.servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `quarkus.atmosphere.session-support` | `false` | Enable HTTP session support |
| `quarkus.atmosphere.broadcaster-class` | (default) | Custom `Broadcaster` implementation |
| `quarkus.atmosphere.broadcaster-cache-class` | (default) | Custom `BroadcasterCache` implementation |
| `quarkus.atmosphere.load-on-startup` | `1` | Servlet load-on-startup order -- **must be > 0** |
| `quarkus.atmosphere.heartbeat-interval-in-seconds` | (default) | Heartbeat interval |
| `quarkus.atmosphere.init-params` | (none) | Map of raw `ApplicationConfig` init params |

> **Note:** `load-on-startup` must be > 0. Quarkus skips servlet initialization when this value is <= 0, unlike the standard Servlet spec where >= 0 means "load on startup."

## Running

```bash
mvn quarkus:dev                          # dev mode with live reload
mvn clean package && java -jar target/quarkus-app/quarkus-run.jar  # JVM
mvn clean package -Pnative               # native image
```

## GraalVM Native Image

```bash
./mvnw -Pnative package -pl samples/quarkus-chat
./samples/quarkus-chat/target/atmosphere-quarkus-chat-*-runner
```

Requires GraalVM JDK 21+ or Mandrel. Use `-Dquarkus.native.container-build=true` to build without a local GraalVM installation.

## Samples

- [Quarkus Chat](../samples/quarkus-chat/) -- real-time chat with WebSocket and long-polling fallback

## See Also

- [Core Runtime](core.md)
- [Spring Boot Integration](spring-boot.md)
- [Module README](../modules/quarkus-extension/README.md)
