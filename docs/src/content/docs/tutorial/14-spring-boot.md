---
title: "Chapter 14: Spring Boot Integration"
description: "Integrate Atmosphere with Spring Boot 4.0 using the auto-configured starter, including gRPC, actuator health, Micrometer metrics, OpenTelemetry tracing, and GraalVM native images."
---

Spring Boot is the most popular way to run Atmosphere in production. The `atmosphere-spring-boot-starter` gives you a single dependency that auto-configures the servlet, wires Spring DI into your `@ManagedService` classes, and lights up observability features the moment their libraries appear on the classpath.

This chapter covers everything from a minimal `application.yml` to GraalVM native image compilation.

## Prerequisites

- JDK 21+
- Spring Boot **4.0.2** (Spring Framework 7.0)
- An existing Spring Boot web application (or we will create one from scratch)

## Adding the Dependency

Add the starter to your `pom.xml`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>4.0.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.atmosphere</groupId>
        <artifactId>atmosphere-spring-boot-starter</artifactId>
        <version>4.0.10</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>jakarta.inject</groupId>
        <artifactId>jakarta.inject-api</artifactId>
    </dependency>
</dependencies>
```

The starter transitively brings in `atmosphere-runtime`. You do not need to add it separately.

## application.yml Configuration

The starter reads properties under the `atmosphere` prefix. Here is a complete reference with defaults:

```yaml
atmosphere:
  # Comma-separated packages to scan for @ManagedService, @AtmosphereHandlerService, etc.
  packages: com.example.realtime

  # URL pattern for the Atmosphere servlet
  servlet-path: /atmosphere/*

  # Servlet load-on-startup order (default: 0)
  order: 0

  # Enable HTTP session tracking
  session-support: false

  # Override the default broadcaster implementation (fully qualified class name)
  broadcaster-class: null

  # Override the default broadcaster cache (fully qualified class name)
  broadcaster-cache-class: null

  # Enable/disable WebSocket transport (null = auto-detect)
  websocket-support: null

  # Heartbeat interval in seconds (null = framework default)
  heartbeat-interval-in-seconds: null

  # Durable session support (requires atmosphere-durable-sessions on classpath)
  durable-sessions:
    enabled: false
    session-ttl-minutes: 1440
    cleanup-interval-seconds: 60

  # gRPC transport (requires atmosphere-grpc on classpath)
  grpc:
    enabled: false
    port: 9090
    enable-reflection: true

  # Additional init parameters passed directly to the servlet
  init-params:
    org.atmosphere.cpr.maxInactiveActivity: "120000"
```

The only property you almost always need is `atmosphere.packages` -- without it, the annotation scanner will not find your `@ManagedService` classes.

## What Gets Auto-Configured

When the starter is on the classpath and `atmosphere.enabled` is not set to `false`, the `AtmosphereAutoConfiguration` class registers these beans:

| Bean | Type | Purpose |
|------|------|---------|
| `atmosphereServlet` | `AtmosphereServlet` | The framework servlet, pre-configured with annotation scanning results |
| `atmosphereFramework` | `AtmosphereFramework` | The core framework instance (extracted from the servlet) |
| `springAtmosphereObjectFactory` | `SpringAtmosphereObjectFactory` | Bridges Spring DI and Atmosphere's object factory |
| `roomManager` | `RoomManager` | Room management, created via `RoomManager.getOrCreate(framework)` |
| `atmosphereLifecycle` | `AtmosphereLifecycle` | Graceful shutdown integration with Spring Boot |
| `atmosphereServletRegistration` | `ServletRegistrationBean` | Registers the servlet with the embedded container |

The `AtmosphereActuatorAutoConfiguration` adds:

| Bean | Condition | Purpose |
|------|-----------|---------|
| `atmosphereHealthIndicator` | `HealthIndicator` on classpath | Reports framework health to `/actuator/health` |

Each bean is `@ConditionalOnMissingBean`, so you can override any of them by declaring your own.

## Spring DI Integration

The `SpringAtmosphereObjectFactory` bridges Spring's `ApplicationContext` with Atmosphere's object lifecycle. Your `@ManagedService` classes can use either `@Inject` (Jakarta CDI) or `@Autowired` (Spring) to inject beans:

```java
@ManagedService(path = "/chat")
public class ChatService {

    // Atmosphere-managed objects -- injected by the framework
    @Inject
    private AtmosphereResource resource;

    @Inject
    private BroadcasterFactory broadcasterFactory;

    // Spring-managed beans -- injected by SpringAtmosphereObjectFactory
    @Autowired
    private UserRepository userRepository;

    @Inject  // @Inject works for Spring beans too
    private NotificationService notificationService;

    @Ready
    public void onReady() {
        var user = userRepository.findBySessionId(resource.uuid());
        notificationService.notifyJoined(user);
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage msg) {
        return msg;
    }
}
```

**How it works:** When Atmosphere creates an instance of your class, the `SpringAtmosphereObjectFactory` first tries to look it up in the Spring `ApplicationContext`. If no Spring bean exists, it creates the object via Spring's `AutowireCapableBeanFactory.createBean()` (which handles `@Autowired`). As a fallback, it delegates to Atmosphere's `InjectableObjectFactory` for framework-managed objects (`AtmosphereResource`, `BroadcasterFactory`, etc.) and then does a second pass to inject any remaining Spring beans into `@Inject` or `@Autowired` fields.

When multiple Spring beans of the same type exist, use `@Qualifier` to disambiguate:

```java
@Inject
@Qualifier("primaryNotifier")
private NotificationService notificationService;
```

## Annotation Scanning in Embedded Containers

Spring Boot's embedded Tomcat/Jetty does not process `@HandlesTypes` from `ServletContainerInitializer`. This means Atmosphere's built-in annotation scanner receives no classes.

The starter works around this by performing classpath scanning at bean creation time using Spring's `ClassPathScanningCandidateComponentProvider`. It scans:

1. The `org.atmosphere` package (to find all framework annotation processors)
2. Every package listed in `atmosphere.packages`
3. Any custom annotation types discovered from `@AtmosphereAnnotation` processors

The discovered classes are injected into the `ServletContext` before `framework.init()` runs, so the framework processes them as if the container had provided them.

**Key takeaway:** Always set `atmosphere.packages` to include your application's base package.

## A Complete Chat Application

Here is the `ChatApplication` main class:

```java
@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
```

The `application.yml`:

```yaml
atmosphere:
  packages: com.example.chat
```

The `@ManagedService`:

```java
@ManagedService(path = "/atmosphere/chat",
                atmosphereConfig = "org.atmosphere.cpr.maxInactiveActivity=120000")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private BroadcasterFactory factory;

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Heartbeat
    public void onHeartbeat(AtmosphereResourceEvent event) {
        logger.trace("Heartbeat from {}", event.getResource());
    }

    @Ready
    public void onReady() {
        logger.info("Browser {} connected", r.uuid());
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isCancelled()) {
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    public Message onMessage(Message message) {
        logger.info("{} just sent {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
```

Run it with `mvn spring-boot:run` and connect to `ws://localhost:8080/atmosphere/chat`.

## gRPC Transport

Atmosphere can expose a gRPC transport alongside the servlet-based transports. This requires the `atmosphere-grpc` module:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-grpc</artifactId>
    <version>4.0.10</version>
</dependency>
```

Enable it in `application.yml`:

```yaml
atmosphere:
  grpc:
    enabled: true
    port: 9090
    enable-reflection: true
```

The `AtmosphereGrpcAutoConfiguration` creates:

- A `GrpcHandler` bean (default: `GrpcHandlerAdapter`)
- An `AtmosphereGrpcServer` bean configured with the port and reflection settings
- A `SmartLifecycle` bean that starts the gRPC server after the servlet container is ready and stops it on shutdown

The gRPC server runs on a separate port (default 9090) and bridges messages into the same `AtmosphereFramework` instance. gRPC reflection is enabled by default so tools like `grpcurl` can discover services:

```bash
grpcurl -plaintext localhost:9090 list
```

## Actuator Health Indicator

When `spring-boot-starter-actuator` is on the classpath, the starter automatically registers an `AtmosphereHealthIndicator`. No additional configuration is required.

The health endpoint at `/actuator/health` includes:

```json
{
  "status": "UP",
  "components": {
    "atmosphere": {
      "status": "UP",
      "details": {
        "version": "4.0.10",
        "handlers": 2,
        "broadcasters": 3,
        "connections": 15,
        "asyncSupport": "JSR356AsyncSupport"
      }
    }
  }
}
```

The indicator reports `DOWN` only when the framework has been destroyed. Zero registered handlers is reported as `UP` with a warning detail (this is expected in GraalVM native images where annotation scanning may be limited).

To include the Atmosphere health check in a Kubernetes readiness group:

```yaml
management:
  endpoint:
    health:
      show-details: always
      group:
        readiness:
          include: readinessState,atmosphere
  endpoints:
    web:
      exposure:
        include: health,metrics
```

## Micrometer Metrics

When `micrometer-core` and `spring-boot-starter-actuator` are both on the classpath, the `AtmosphereMetricsAutoConfiguration` automatically installs `AtmosphereMetrics`. No code is required.

Add the dependency:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

Metrics are published under the `atmosphere.*` namespace:

| Metric | Type | Description |
|--------|------|-------------|
| `atmosphere.connections` | Gauge | Current number of connected resources |
| `atmosphere.broadcasters` | Gauge | Number of active broadcasters |
| `atmosphere.messages.sent` | Counter | Total messages broadcast |
| `atmosphere.messages.received` | Counter | Total messages received |

Query them via the Actuator metrics endpoint:

```bash
curl http://localhost:8080/actuator/metrics/atmosphere.connections
```

Or integrate with Prometheus, Datadog, or any Micrometer-supported backend by adding the appropriate registry dependency.

## OpenTelemetry Tracing

When `opentelemetry-api` is on the classpath and an `OpenTelemetry` bean is available, the `AtmosphereTracingAutoConfiguration` registers an `AtmosphereTracing` interceptor.

Add the dependencies:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Enable the Spring Boot OTel auto-configuration:

```yaml
spring:
  application:
    name: my-chat-app

management:
  tracing:
    sampling:
      probability: 1.0

otel:
  exporter:
    otlp:
      endpoint: http://localhost:4318
```

The tracing interceptor creates spans that cover the full Atmosphere request lifecycle: inspect, suspend, broadcast, and disconnect. Each span includes attributes for:

- Transport type (WebSocket, SSE, long-polling)
- Resource UUID
- Broadcaster ID
- Disconnect reason

Tracing can be disabled without removing dependencies:

```yaml
atmosphere:
  tracing:
    enabled: false
```

When the `atmosphere-mcp` module is also on the classpath, an `McpTracing` bean is automatically created that wraps MCP tool, resource, and prompt calls in trace spans.

## Graceful Shutdown

The `AtmosphereLifecycle` bean participates in Spring Boot's shutdown lifecycle. During shutdown:

1. It publishes `ReadinessState.REFUSING_TRAFFIC` so Kubernetes readiness probes start failing
2. It calls `AtmosphereFramework.destroy()`, which disconnects all resources and cleans up thread pools
3. Then the servlet container shuts down

This runs at `SmartLifecycle.DEFAULT_PHASE - 1`, ensuring Atmosphere stops before the web server.

No configuration is needed -- this is automatic.

## GraalVM Native Image

The starter includes `AtmosphereRuntimeHints` which registers reflection hints for all Atmosphere classes that are instantiated reflectively at runtime:

- Core framework classes (`AtmosphereFramework`, `DefaultBroadcaster`, `DefaultBroadcasterFactory`, etc.)
- Injectable SPI implementations
- Default interceptors (`CorsInterceptor`, `HeartbeatInterceptor`, `SSEAtmosphereInterceptor`, etc.)
- Annotation processors
- WebSocket internals (`JSR356Endpoint`, `DefaultWebSocketProcessor`, etc.)
- ServiceLoader resource files

To build a native image, add the Spring Boot AOT and GraalVM plugins:

```xml
<profiles>
    <profile>
        <id>native</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration>
                        <classifier>exec</classifier>
                    </configuration>
                    <executions>
                        <execution>
                            <id>process-aot</id>
                            <goals>
                                <goal>process-aot</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.graalvm.buildtools</groupId>
                    <artifactId>native-maven-plugin</artifactId>
                    <configuration>
                        <mainClass>com.example.ChatApplication</mainClass>
                    </configuration>
                    <executions>
                        <execution>
                            <id>build-native</id>
                            <goals>
                                <goal>compile-no-fork</goal>
                            </goals>
                            <phase>package</phase>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

Build with:

```bash
./mvnw -Pnative package
```

Run the native executable:

```bash
./target/my-app
```

Startup time is typically under 100ms.

## Overriding Auto-Configuration

Every auto-configured bean uses `@ConditionalOnMissingBean`. To override any of them, declare your own bean of the same type:

```java
@Configuration
public class CustomAtmosphereConfig {

    @Bean
    public AtmosphereServlet atmosphereServlet(SpringAtmosphereObjectFactory objectFactory) {
        var servlet = new AtmosphereServlet();
        servlet.framework().objectFactory(objectFactory);
        // Custom framework configuration
        servlet.framework().addInitParameter(
            ApplicationConfig.BROADCASTER_CACHE, "com.example.MyBroadcasterCache");
        return servlet;
    }
}
```

To disable the entire Atmosphere auto-configuration:

```yaml
atmosphere:
  enabled: false
```

## Passing Arbitrary Init Parameters

For any Atmosphere configuration property that does not have a dedicated YAML key, use the `init-params` map:

```yaml
atmosphere:
  init-params:
    org.atmosphere.cpr.maxInactiveActivity: "120000"
    org.atmosphere.cpr.broadcasterLifeCyclePolicy: IDLE_DESTROY
    org.atmosphere.websocket.maxTextMessageSize: "65536"
```

These are passed directly to the `ServletRegistrationBean` as servlet init parameters.

## Sample Application

The `samples/spring-boot-chat` directory contains a complete working chat application. To run it:

```bash
cd samples/spring-boot-chat
../../mvnw spring-boot:run
```

Open `http://localhost:8080` in a browser and start chatting. The sample includes:

- A `@ManagedService`-based chat with Jackson encoding/decoding
- Spring Boot Actuator with health and metrics endpoints
- Micrometer metrics installation via `ObservabilityConfig`
- Room management with the REST endpoint (`ChatRoomsController`)
- A React frontend in `frontend/`

## Summary

The `atmosphere-spring-boot-starter` gives you:

- **Zero-config setup** -- add the dependency, set `atmosphere.packages`, done
- **Full Spring DI** -- `@Inject` and `@Autowired` work in `@ManagedService` classes
- **Observability out of the box** -- health, metrics, and tracing auto-register when their libraries are present
- **Graceful shutdown** -- Kubernetes-ready lifecycle management
- **Native image support** -- reflection hints pre-registered for GraalVM

Next up: [Chapter 15: Quarkus Integration](/docs/tutorial/15-quarkus/) covers the same concepts for Quarkus 3.21.
