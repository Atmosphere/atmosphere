---
title: "Spring Boot Integration"
description: "Integrate Atmosphere with Spring Boot 4.0 using the auto-configured starter"
sidebar:
  order: 14
---

The `atmosphere-spring-boot-starter` module provides auto-configuration for Spring Boot 4.0.2 (Spring Framework 7.0). Add it as a dependency and your `@ManagedService` classes work out of the box.

## Dependency

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

## What the starter auto-configures

The `AtmosphereAutoConfiguration` class activates when:

- The application is a servlet-based web application.
- `AtmosphereServlet` is on the classpath.
- The property `atmosphere.enabled` is not set to `false` (defaults to `true`).

It registers the following beans:

| Bean | Type | Description |
|------|------|-------------|
| `atmosphereServlet` | `AtmosphereServlet` | The core Atmosphere servlet, with annotation scanning pre-configured |
| `atmosphereFramework` | `AtmosphereFramework` | The framework instance, extracted from the servlet |
| `springAtmosphereObjectFactory` | `SpringAtmosphereObjectFactory` | Bridges Spring's `ApplicationContext` to Atmosphere's object factory, enabling `@Inject` in `@ManagedService` classes |
| `roomManager` | `RoomManager` | The Rooms API manager |
| `atmosphereLifecycle` | `AtmosphereLifecycle` | Handles framework lifecycle (startup/shutdown) |
| `atmosphereServletRegistration` | `ServletRegistrationBean` | Registers the servlet with the embedded container |

The starter exposes `AtmosphereFramework` as a bean, but does **not** expose `BroadcasterFactory` as a bean. To access it, inject `AtmosphereFramework` and call `framework.getAtmosphereConfig().getBroadcasterFactory()`, or use `@Inject BroadcasterFactory` inside `@ManagedService` classes (which goes through Atmosphere's own injection).

### Annotation scanning

Spring Boot's embedded containers do not process `@HandlesTypes` from `ServletContainerInitializer`, so Atmosphere's built-in annotation scanning receives no classes. The starter bridges this gap by using Spring's `ClassPathScanningCandidateComponentProvider` to scan for all Atmosphere annotations (including `@ManagedService`, `@AtmosphereHandlerService`, `@BroadcasterFilterService`, `@RoomService`, and many more) and injecting the results into the servlet context before `framework.init()` reads them.

The starter also performs a second pass to discover custom annotation types registered via `@AtmosphereAnnotation` processors (e.g., the AI module's `@AiEndpoint` processor), and re-scans user packages for classes annotated with those custom annotations.

## Application class

The application class is a standard Spring Boot entry point:

```java
@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
```

## @ManagedService

`@ManagedService` works exactly as documented in earlier chapters. Spring's `ApplicationContext` is wired via `SpringAtmosphereObjectFactory`, so `@Inject` resolves both Atmosphere-managed objects (like `AtmosphereResource`, `Broadcaster`) and Spring beans.

```java
@ManagedService(path = "/atmosphere/chat",
                atmosphereConfig = MAX_INACTIVE + "=120000")
public class Chat {

    @Inject
    @Named("/atmosphere/chat")
    private Broadcaster broadcaster;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(final AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected",
                event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection",
                event.getResource().uuid());
        }
    }

    @Message(encoders = {JacksonEncoder.class},
             decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

## Configuration properties

Configure Atmosphere via `application.properties` or `application.yml` under the `atmosphere` prefix:

| Property | Default | Description |
|----------|---------|-------------|
| `atmosphere.servlet-path` | `/atmosphere/*` | URL pattern for the Atmosphere servlet |
| `atmosphere.packages` | (none) | Comma-separated packages to scan for Atmosphere annotations |
| `atmosphere.order` | `0` | `loadOnStartup` order for the servlet |
| `atmosphere.session-support` | `false` | Enable HTTP session support |
| `atmosphere.broadcaster-class` | (none) | Custom `Broadcaster` implementation class name |
| `atmosphere.broadcaster-cache-class` | (none) | Custom `BroadcasterCache` implementation class name |
| `atmosphere.websocket-support` | (auto) | Explicitly enable/disable WebSocket support |
| `atmosphere.heartbeat-interval-in-seconds` | (auto) | Heartbeat interval |
| `atmosphere.init-params.*` | (none) | Additional Atmosphere init parameters |
| `atmosphere.enabled` | `true` | Enable/disable the auto-configuration |
| `atmosphere.durable-sessions.enabled` | `false` | Enable durable sessions |
| `atmosphere.durable-sessions.session-ttl-minutes` | `1440` | Durable session TTL |
| `atmosphere.durable-sessions.cleanup-interval-seconds` | `60` | Cleanup interval |
| `atmosphere.grpc.enabled` | `false` | Enable gRPC transport |
| `atmosphere.grpc.port` | `9090` | gRPC server port |
| `atmosphere.grpc.enable-reflection` | `true` | Enable gRPC reflection |

### Example application.yml

From the `spring-boot-chat` sample:

```yaml
atmosphere:
  packages: org.atmosphere.samples.springboot.chat

management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: always
```

## Rooms configuration

The `RoomsConfig` pattern from the `spring-boot-chat` sample shows how to set up Atmosphere Rooms with Spring Boot:

```java
@Configuration
public class RoomsConfig {

    private final AtmosphereFramework framework;

    public RoomsConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    @Bean
    public RoomManager roomManager() {
        return RoomManager.getOrCreate(framework);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupRooms() {
        var interceptor = new RoomProtocolInterceptor();
        interceptor.configure(framework.getAtmosphereConfig());
        framework.interceptor(interceptor);

        RoomManager manager = roomManager();
        Room lobby = manager.room("lobby");
        lobby.enableHistory(50);

        lobby.onPresence(event -> {
            var memberInfo = event.memberInfo();
            var memberId = memberInfo != null
                    ? memberInfo.id() : event.member().uuid();
            logger.info("Room '{}': {} {} (members: {})",
                    event.room().name(), memberId,
                    event.type(), event.room().size());
        });
    }
}
```

Key patterns:

- Inject `AtmosphereFramework` directly (it is a Spring bean).
- Use `@EventListener(ApplicationReadyEvent.class)` to configure after the framework is initialized.
- Register interceptors via `framework.interceptor()`.

## Observability configuration

The `ObservabilityConfig` pattern installs Micrometer metrics:

```java
@Configuration
public class ObservabilityConfig {

    private final AtmosphereFramework framework;
    private final MeterRegistry meterRegistry;

    public ObservabilityConfig(AtmosphereFramework framework,
                               MeterRegistry meterRegistry) {
        this.framework = framework;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installMetrics() {
        AtmosphereMetrics.install(framework, meterRegistry);
    }
}
```

After installation, metrics are available at `/actuator/metrics/atmosphere.*` and health at `/actuator/health`.

## REST + Atmosphere

Spring `@RestController` classes can interact with Atmosphere's `RoomManager`:

```java
@RestController
@RequestMapping("/api/rooms")
public class ChatRoomsController {

    private final RoomManager roomManager;

    public ChatRoomsController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        return roomManager.all().stream()
                .map(room -> {
                    var map = new HashMap<String, Object>();
                    map.put("name", room.name());
                    map.put("members", room.size());
                    map.put("destroyed", room.isDestroyed());
                    var memberList = room.memberInfo().values().stream()
                            .map(m -> Map.of(
                                    "id", (Object) m.id(),
                                    "metadata", (Object) m.metadata()))
                            .toList();
                    map.put("memberDetails", memberList);
                    return map;
                })
                .toList();
    }
}
```

This pattern works because `RoomManager` is exposed as a Spring bean by the auto-configuration.

## Important notes

**Object factory order**: The starter sets the `SpringAtmosphereObjectFactory` on the `AtmosphereFramework` **before** calling `init()`. This is critical -- if the object factory is set after init, Atmosphere's annotation processors will not be able to inject Spring beans into `@ManagedService` instances.

**Spring Boot 4.0 target**: The starter is built for Spring Boot 4.0.2 and Spring Framework 7.0. It overrides the parent POM's SLF4J/Logback versions for compatibility.

**BroadcasterFactory is not a Spring bean**: The starter deliberately does not expose `BroadcasterFactory` as a Spring bean. It is available via Atmosphere's `@Inject` inside `@ManagedService` classes, or programmatically via `framework.getAtmosphereConfig().getBroadcasterFactory()`.

**`atmosphere.packages` is required** for annotation scanning to find your `@ManagedService` classes. Set it to the package(s) containing your Atmosphere-annotated classes.
