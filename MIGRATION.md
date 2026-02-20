# Migrating to Atmosphere 4.0

This guide covers upgrading from Atmosphere 2.x or 3.x to Atmosphere 4.0. It is
organized by topic so you can jump to the sections relevant to your application.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Dependency Changes](#2-dependency-changes)
3. [Package Renames (javax to jakarta)](#3-package-renames-javax-to-jakarta)
4. [Configuration Changes](#4-configuration-changes)
5. [Server-Side API Changes](#5-server-side-api-changes)
6. [Client Library Migration (atmosphere.js)](#6-client-library-migration-atmospherejs)
7. [Server Container Requirements](#7-server-container-requirements)
8. [New Features in 4.0](#8-new-features-in-40)
9. [Testing](#9-testing)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Prerequisites

### JDK Version

Atmosphere 4.0 requires **JDK 21 or later**. Previous versions supported JDK 8+.

```bash
# Verify your JDK version
java -version
# Must show 21 or later
```

### Jakarta EE 10+ Container

Atmosphere 4.0 targets **Jakarta Servlet 6.0** (Jakarta EE 10). You need a
container that implements these APIs:

| Container | Minimum Version |
|-----------|----------------|
| Apache Tomcat | 11.0+ |
| Eclipse Jetty | 12.0+ (EE10 module) |
| Undertow (standalone) | 2.3+ |
| Quarkus | 3.21+ (via `atmosphere-quarkus-extension`) |
| Spring Boot | 4.0+ (via `atmosphere-spring-boot-starter`) |

Older containers (Tomcat 9/10, Jetty 9/10/11, WildFly 26 and below) are **not
supported**.

### Maven Version

Maven 3.6.3 or later is required (enforced by the build).

---

## 2. Dependency Changes

### Core Runtime

The `groupId` and `artifactId` are unchanged. Only the version changes.

**Before (2.x / 3.x):**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>2.7.14</version>  <!-- or 3.0.x -->
</dependency>
```

**After (4.0):**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>4.0.0</version>
</dependency>
```

### Servlet API Dependency

The Servlet API dependency has changed from `javax.servlet` to `jakarta.servlet`:

**Before:**

```xml
<dependency>
    <groupId>javax.servlet</groupId>
    <artifactId>javax.servlet-api</artifactId>
    <version>4.0.1</version>
    <scope>provided</scope>
</dependency>
```

**After:**

```xml
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <version>5.0.0</version>
    <scope>provided</scope>
</dependency>
```

### WebSocket API Dependency

```xml
<!-- Before -->
<dependency>
    <groupId>javax.websocket</groupId>
    <artifactId>javax.websocket-api</artifactId>
    <version>1.1</version>
    <scope>provided</scope>
</dependency>

<!-- After -->
<dependency>
    <groupId>jakarta.websocket</groupId>
    <artifactId>jakarta.websocket-api</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
```

### Removed Dependencies

The following external dependencies and integration modules from older Atmosphere
releases are **no longer shipped** as part of the core distribution. If you were
using them, check the new dedicated modules:

| Old Dependency / Module | Replacement |
|------------------------|-------------|
| `atmosphere-redis` (2.x plugin) | `atmosphere-redis` (new module, `org.atmosphere:atmosphere-redis:4.0.0`) |
| `atmosphere-kafka` (external) | `atmosphere-kafka` (new module, `org.atmosphere:atmosphere-kafka:4.0.0`) |
| `atmosphere-jgroups` | Removed. Use Redis or Kafka for clustering. |
| `atmosphere-jms` | Removed. Use Redis or Kafka for clustering. |
| `atmosphere-hazelcast` | Removed. Use Redis or Kafka for clustering. |
| `atmosphere-rabbitmq` | Removed. Use Redis or Kafka for clustering. |
| `atmosphere-xmpp` | Removed. |
| `atmosphere-jersey` | Removed. Use `@ManagedService` or `@AtmosphereService` directly. |

### New Optional Modules

Atmosphere 4.0 introduces several new optional modules:

```xml
<!-- Spring Boot auto-configuration -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Quarkus extension -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- AI/LLM streaming SPI -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Redis clustering -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Kafka clustering -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Durable sessions (survives server restart) -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Durable sessions backed by SQLite -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-sqlite</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Durable sessions backed by Redis -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-redis</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- MCP (Model Context Protocol) support -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Spring AI integration -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-ai</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- LangChain4j integration -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-langchain4j</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Kotlin extensions -->
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kotlin</artifactId>
    <version>4.0.0</version>
</dependency>
```

---

## 3. Package Renames (javax to jakarta)

This is the most impactful change for existing code. **Every** `javax.servlet`
and `javax.websocket` import must be changed to the `jakarta` namespace.

### Search and Replace

Run this across your codebase:

| Old Import | New Import |
|-----------|-----------|
| `javax.servlet.*` | `jakarta.servlet.*` |
| `javax.websocket.*` | `jakarta.websocket.*` |
| `javax.inject.*` | `jakarta.inject.*` |
| `javax.enterprise.*` | `jakarta.enterprise.*` |

**Example:**

```java
// Before
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

// After
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
```

Atmosphere's own packages (`org.atmosphere.*`) are **unchanged**.

### Automated Migration

For large codebases, use an IDE migration tool or a script:

```bash
# Linux/macOS - update all Java files
find src -name "*.java" -exec sed -i '' \
  -e 's/javax\.servlet/jakarta.servlet/g' \
  -e 's/javax\.websocket/jakarta.websocket/g' \
  -e 's/javax\.inject/jakarta.inject/g' \
  {} +
```

### web.xml Namespace

If you use a `web.xml`, update the namespace:

**Before:**

```xml
<web-app version="3.0" xmlns="http://java.sun.com/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://java.sun.com/xml/ns/javaee
         http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd">
```

**After:**

```xml
<web-app version="6.0" xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
         https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd">
```

---

## 4. Configuration Changes

### web.xml Configuration

The core servlet class names are unchanged. The main required change is the
namespace (see above).

```xml
<servlet>
    <servlet-name>AtmosphereServlet</servlet-name>
    <!-- Class name is unchanged -->
    <servlet-class>org.atmosphere.cpr.AtmosphereServlet</servlet-class>
    <init-param>
        <param-name>org.atmosphere.cpr.packages</param-name>
        <param-value>com.yourapp.atmosphere</param-value>
    </init-param>
    <load-on-startup>0</load-on-startup>
    <async-supported>true</async-supported>
</servlet>
<servlet-mapping>
    <servlet-name>AtmosphereServlet</servlet-name>
    <url-pattern>/atmosphere/*</url-pattern>
</servlet-mapping>
```

All `org.atmosphere.*` init-param names remain the same. The key configuration
constants in `ApplicationConfig` are unchanged.

### New Configuration Parameters

Atmosphere 4.0 adds several new init-params:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `org.atmosphere.useVirtualThreads` | `true` | Use JDK 21 virtual threads for message dispatching. Set to `false` to use platform thread pools. |
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis URL for `RedisBroadcaster` |
| `org.atmosphere.redis.password` | (none) | Redis password |
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka bootstrap servers for `KafkaBroadcaster` |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Kafka topic name prefix |
| `org.atmosphere.kafka.group.id` | (auto-generated) | Kafka consumer group ID |

### Spring Boot Configuration (New)

If you are migrating to Spring Boot 4.0, you can replace your `web.xml` and manual
servlet registration entirely with auto-configuration.

**Before (manual servlet registration in Spring Boot 2.x/3.x):**

```java
@Configuration
public class AtmosphereConfig {
    @Bean
    public AtmosphereServlet atmosphereServlet() {
        return new AtmosphereServlet();
    }

    @Bean
    public ServletRegistrationBean<AtmosphereServlet> registration(AtmosphereServlet servlet) {
        ServletRegistrationBean<AtmosphereServlet> reg =
            new ServletRegistrationBean<>(servlet, "/atmosphere/*");
        reg.setLoadOnStartup(0);
        reg.setAsyncSupported(true);
        reg.addInitParameter("org.atmosphere.cpr.packages", "com.yourapp");
        return reg;
    }
}
```

**After (Spring Boot 4.0 with auto-configuration):**

Just add the starter dependency and configure via `application.yml`:

```yaml
atmosphere:
  packages: com.yourapp.atmosphere
  servlet-path: /atmosphere/*
  session-support: false
  heartbeat-interval-in-seconds: 60
  init-params:
    org.atmosphere.websocket.maxIdleTime: "300000"
```

No `@Configuration` class needed. The auto-configuration:
- Registers the `AtmosphereServlet` with async support
- Injects Spring beans via `SpringAtmosphereObjectFactory`
- Scans for Atmosphere annotations (`@ManagedService`, `@RoomService`, etc.)
- Exposes `AtmosphereFramework` and `RoomManager` as Spring beans
- Optionally integrates with Spring Boot Actuator for health checks

### Quarkus Configuration (New)

For Quarkus 3.21+, use the extension instead of `web.xml`:

```properties
# application.properties
quarkus.atmosphere.packages=com.yourapp.atmosphere
quarkus.atmosphere.servlet-path=/atmosphere/*
quarkus.atmosphere.load-on-startup=1
quarkus.atmosphere.session-support=false
```

Note: `load-on-startup` must be greater than 0 in Quarkus (Quarkus skips servlet
initialization when the value is 0 or negative).

---

## 5. Server-Side API Changes

### Annotations

All existing Atmosphere annotations remain and work as before:

- `@ManagedService` -- still the primary way to create Atmosphere endpoints
- `@AtmosphereHandlerService`
- `@AtmosphereService`
- `@WebSocketHandlerService`
- `@Ready`, `@Disconnect`, `@Message`, `@Heartbeat`

New annotations in 4.0:

| Annotation | Module | Description |
|-----------|--------|-------------|
| `@RoomService` | `atmosphere-runtime` | Like `@ManagedService` but scoped to a `Room`. Supports path params (e.g., `/chat/{roomId}`). |
| `@AiEndpoint` | `atmosphere-ai` | Eliminates boilerplate for AI streaming endpoints. Pair with `@Prompt`. |
| `@Prompt` | `atmosphere-ai` | Marks the method that receives user messages in an `@AiEndpoint`. |

**@RoomService example:**

```java
@RoomService(path = "/chat/{roomId}")
public class ChatRoom {

    @Ready
    public void onJoin(AtmosphereResource r) {
        // invoked when a client joins the room
    }

    @Message
    public String onMessage(String message) {
        return message; // broadcast to all room members
    }

    @Disconnect
    public void onLeave(AtmosphereResourceEvent event) {
        // invoked when a client disconnects
    }
}
```

**@AiEndpoint example:**

```java
@AiEndpoint(path = "/atmosphere/ai-chat",
            systemPrompt = "You are a helpful assistant.")
public class MyAiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        // Stream tokens back to the client
        session.token("Hello ");
        session.token("World!");
        session.complete();
    }
}
```

### AtmosphereServlet

The class `org.atmosphere.cpr.AtmosphereServlet` is unchanged. It still extends
`jakarta.servlet.http.HttpServlet` (was `javax.servlet.http.HttpServlet`).

### AtmosphereFramework

The `AtmosphereFramework` class API is largely unchanged. Key points:

- `framework.init()` still works the same way
- `framework.addAtmosphereHandler()` still works
- `framework.objectFactory()` now accepts a Spring or CDI object factory

**Internal refactoring (4.0):** `AtmosphereFramework` has been decomposed
into focused component classes (`BroadcasterSetup`, `ClasspathScanner`,
`InterceptorRegistry`, `HandlerRegistry`, `WebSocketConfig`,
`FrameworkEventDispatcher`, `FrameworkDiagnostics`). The public API is
fully preserved -- no application code changes are required.

If your code accesses **`AtmosphereHandlerWrapper`** fields directly:

```java
// 3.x -- direct field access
wrapper.broadcaster = myBroadcaster;
wrapper.interceptors.add(myInterceptor);
handler = wrapper.atmosphereHandler;

// 4.0 -- use accessor methods
wrapper.setBroadcaster(myBroadcaster);
wrapper.interceptors().add(myInterceptor);
handler = wrapper.atmosphereHandler();
```

### Broadcaster

The `DefaultBroadcaster` API is unchanged. One behavioral change:

- **Virtual threads by default.** `DefaultBroadcaster` now uses `ReentrantLock`
  instead of `synchronized` to avoid pinning virtual threads. This is transparent
  to application code, but if you have custom `Broadcaster` implementations that
  use `synchronized` blocks with blocking I/O, consider switching to
  `ReentrantLock`.
- Thread pool sizes (`maxProcessingThreads`, `maxAsyncWriteThreads`) are ignored
  when virtual threads are enabled because virtual threads do not use bounded
  pools. Set `org.atmosphere.useVirtualThreads=false` to restore the old behavior.

### Room API (New)

Atmosphere 4.0 adds a high-level `Room` abstraction on top of `Broadcaster`:

```java
RoomManager rooms = RoomManager.getOrCreate(framework);
Room lobby = rooms.room("lobby");

// Join a resource to a room
lobby.join(resource);

// Broadcast to all room members
lobby.broadcast("Hello everyone!");

// Send to a specific member by UUID
lobby.sendTo("some message", targetUuid);

// Track presence events
lobby.onPresence(event -> {
    System.out.println(event.member() + " " + event.type());
});

// Virtual members (e.g., AI agents)
lobby.joinVirtual(myVirtualMember);
```

### Observability (New)

Atmosphere 4.0 has optional Micrometer and OpenTelemetry support built into the
core runtime (optional dependencies):

```java
// Micrometer metrics
MeterRegistry registry = new SimpleMeterRegistry();
AtmosphereMetrics.install(framework, registry);

// Published metrics:
// atmosphere.connections.active   (gauge)
// atmosphere.connections.total    (counter)
// atmosphere.connections.disconnects (counter)
// atmosphere.broadcasters.active  (gauge)
// atmosphere.messages.broadcast   (counter)
// atmosphere.messages.delivered   (counter)
```

### Clustering

Clustering is now handled through dedicated modules instead of external plugins:

**Before (2.x with atmosphere-redis plugin):**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>2.7.x</version>
</dependency>
```

**After (4.0):**

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.0</version>
</dependency>
```

Configuration is done via init-params:

```xml
<init-param>
    <param-name>org.atmosphere.redis.url</param-name>
    <param-value>redis://your-redis-host:6379</param-value>
</init-param>
```

---

## 6. Client Library Migration (atmosphere.js)

This is a **breaking change**. The old `atmosphere.js` (jQuery-based, 2.x/3.x)
has been replaced with a modern TypeScript library (5.0.0).

### Installation

**Before (2.x / 3.x):**

```html
<script src="atmosphere.js"></script>
<!-- or -->
<script src="jquery.atmosphere.js"></script>
```

**After (5.0):**

```bash
npm install atmosphere.js
```

### Import Style

**Before (global `atmosphere` object):**

```javascript
var socket = atmosphere;
var request = new atmosphere.AtmosphereRequest();
request.url = '/chat';
request.transport = 'websocket';
request.fallbackTransport = 'long-polling';

request.onOpen = function(response) { /* ... */ };
request.onMessage = function(response) { /* ... */ };
request.onClose = function(response) { /* ... */ };
request.onError = function(response) { /* ... */ };

var subSocket = socket.subscribe(request);
subSocket.push(JSON.stringify(msg));
```

**After (ES module with TypeScript):**

```typescript
import { Atmosphere } from 'atmosphere.js';

const atmosphere = new Atmosphere();

const subscription = await atmosphere.subscribe(
  {
    url: '/chat',
    transport: 'websocket',
    fallbackTransport: 'long-polling',
  },
  {
    open: (response) => { /* ... */ },
    message: (response) => {
      const data = response.responseBody;
      // ...
    },
    close: (response) => { /* ... */ },
    error: (error) => { /* ... */ },
    reconnect: (request, response) => { /* ... */ },
    transportFailure: (reason, request) => { /* ... */ },
  }
);

subscription.push(JSON.stringify(msg));

// Or push objects directly (auto-serialized to JSON)
subscription.push({ author: 'user', message: 'hello' });
```

### Key API Differences

| Feature | 2.x / 3.x | 5.0 |
|---------|-----------|-----|
| Module system | Global / AMD | ES modules + CommonJS |
| Language | JavaScript | TypeScript (with full type definitions) |
| jQuery dependency | Required for some transports | None |
| Subscribe | `atmosphere.subscribe(request)` | `await atmosphere.subscribe(request, handlers)` |
| Callbacks | Properties on request (`request.onOpen`) | Separate handlers object |
| Push | `subSocket.push(string)` | `subscription.push(string \| object \| ArrayBuffer)` |
| Close | `subSocket.close()` | `await subscription.close()` |
| Close all | `atmosphere.unsubscribe()` | `await atmosphere.closeAll()` |
| Transport types | `'websocket'`, `'sse'`, `'long-polling'`, `'streaming'`, `'jsonp'` | `'websocket'`, `'sse'`, `'long-polling'`, `'streaming'` (JSONP removed) |
| Fallback | Single fallback | Configurable fallback: e.g. WebSocket → SSE or WebSocket → Long-Polling |
| Event system | Callbacks only | Callbacks + `subscription.on('event', handler)` |
| Interceptors | N/A | `AtmosphereInterceptor` interface for transforming outgoing/incoming messages |
| Reconnect config | `request.reconnectInterval` | Same, plus `maxReconnectOnClose`, `maxWebsocketErrorRetries` |

### React / Vue / Svelte Hooks (New)

The 5.0 client ships with framework-specific hooks:

**React:**

```tsx
import { AtmosphereProvider, useAtmosphere, useRoom } from 'atmosphere.js/react';

function App() {
  return (
    <AtmosphereProvider>
      <ChatComponent />
    </AtmosphereProvider>
  );
}

function ChatComponent() {
  const { data, state, push } = useAtmosphere<ChatMessage>({
    request: { url: '/atmosphere/chat', transport: 'websocket' },
  });

  return (
    <div>
      <p>State: {state}</p>
      {data && <p>Last message: {JSON.stringify(data)}</p>}
      <button onClick={() => push({ text: 'Hello!' })}>Send</button>
    </div>
  );
}
```

**Room support in React:**

```tsx
import { useRoom } from 'atmosphere.js/react';

function ChatRoom() {
  const { members, messages, broadcast, joined } = useRoom<ChatMessage>({
    request: { url: '/atmosphere/room', transport: 'websocket' },
    room: 'lobby',
    member: { id: 'user-1' },
  });

  return (
    <div>
      <p>Members: {members.map(m => m.id).join(', ')}</p>
      {messages.map((m, i) => <p key={i}>{m.member.id}: {JSON.stringify(m.data)}</p>)}
      <button onClick={() => broadcast({ text: 'Hello room!' })}>Send</button>
    </div>
  );
}
```

**Vue:**

```vue
<script setup lang="ts">
import { useAtmosphere } from 'atmosphere.js/vue';

const { data, state, push } = useAtmosphere({
  request: { url: '/atmosphere/chat', transport: 'websocket' },
});
</script>
```

### AI Streaming Client (New)

For `@AiEndpoint` server endpoints:

```typescript
import { atmosphere, subscribeStreaming } from 'atmosphere.js';

const handle = await subscribeStreaming(atmosphere, {
  url: '/atmosphere/ai-chat',
  transport: 'websocket',
}, {
  onToken: (token) => process.stdout.write(token),
  onProgress: (msg) => console.log('Progress:', msg),
  onComplete: (summary) => console.log('\nDone!', summary),
  onError: (err) => console.error(err),
  onMetadata: (key, value) => console.log(`${key}: ${value}`),
});

handle.send('What is Atmosphere?');
```

### Room Protocol Client (New)

For `@RoomService` server endpoints:

```typescript
import { Atmosphere, AtmosphereRooms } from 'atmosphere.js';

const atmosphere = new Atmosphere();
const rooms = new AtmosphereRooms(atmosphere, {
  url: '/atmosphere/room',
  transport: 'websocket',
});

const lobby = await rooms.join('lobby', { id: 'user-1' }, {
  message: (data, member) => console.log(`${member.id}: ${data}`),
  join: (event) => console.log(`${event.member.id} joined`),
  leave: (event) => console.log(`${event.member.id} left`),
  joined: (roomName, memberList) => console.log(`Joined ${roomName} with`, memberList),
});

lobby.broadcast('Hello everyone!');
lobby.sendTo('user-2', 'Private message');
lobby.leave();
```

---

## 7. Server Container Requirements

### Minimum Container Versions

| Container | Minimum Version | Servlet API | Notes |
|-----------|----------------|-------------|-------|
| Tomcat | 11.0.x | 6.0 | Requires Jakarta EE 10 |
| Jetty | 12.0.x | 5.0/6.0 | Use `jetty-ee10-servlet` or `jetty-ee11-servlet` module |
| Undertow | 2.3.x | 5.0 | Used by Quarkus internally |
| WildFly | 30+ | 6.0 | |

### Removed Container Support

The following containers are no longer supported:

- Tomcat 7, 8, 8.5, 9, 10, 10.1
- Jetty 7, 8, 9, 10, 11
- GlassFish 3, 4, 5 (use GlassFish 7+ or Payara 6+)
- JBoss AS 7 / WildFly 8-29
- WebLogic 12c (use WebLogic 14.1.2+ with Jakarta EE 10)

### Embedded Jetty 12 Example

If you embed Jetty, you need to update to the Jetty 12 API:

**Before (Jetty 9/10/11):**

```java
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
```

**After (Jetty 12 EE10):**

```java
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

Server server = new Server();
ServerConnector connector = new ServerConnector(server);
connector.setPort(8080);
server.addConnector(connector);

ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
context.setContextPath("/");

// Configure WebSocket FIRST (before AtmosphereServlet init)
JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) -> {
    // WebSocket ServerContainer is now available
});

// Add AtmosphereServlet
ServletHolder atmosphereServlet = new ServletHolder(AtmosphereServlet.class);
atmosphereServlet.setInitParameter(ApplicationConfig.ANNOTATION_PACKAGE, "com.yourapp");
atmosphereServlet.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
atmosphereServlet.setInitOrder(1);
atmosphereServlet.setAsyncSupported(true);
context.addServlet(atmosphereServlet, "/atmosphere/*");

server.setHandler(context);
server.start();
```

---

## 8. New Features in 4.0

### Virtual Threads (Default)

Atmosphere 4.0 uses JDK 21 virtual threads by default for all message dispatching
and async write operations. This provides near-unlimited scalability without
configuring thread pool sizes.

- `ExecutorsFactory` creates `Executors.newVirtualThreadPerTaskExecutor()` by default
- Thread pool size configurations (`maxProcessingThreads`, `maxAsyncWriteThreads`) are
  ignored when virtual threads are enabled
- `DefaultBroadcaster` uses `ReentrantLock` instead of `synchronized` to avoid
  pinning virtual threads
- Only `ScheduledExecutorService` (used for timed tasks like heartbeats) remains on
  platform threads, which is expected

To opt out:

```xml
<init-param>
    <param-name>org.atmosphere.useVirtualThreads</param-name>
    <param-value>false</param-value>
</init-param>
```

### Rooms with Presence Tracking

The `org.atmosphere.room` package provides a higher-level abstraction over
broadcasters. See the `Room` interface, `RoomManager`, `RoomMember`, and
`PresenceEvent` classes.

### Durable Sessions

Sessions that survive server restarts, with pluggable storage backends:

- `InMemorySessionStore` (default, development only)
- `SqliteSessionStore` (requires `atmosphere-durable-sessions-sqlite`)
- `RedisSessionStore` (requires `atmosphere-durable-sessions-redis`)

Spring Boot configuration:

```yaml
atmosphere:
  durable-sessions:
    enabled: true
    session-ttl-minutes: 1440
    cleanup-interval-seconds: 60
```

### AI/LLM Streaming

The `atmosphere-ai` module provides `@AiEndpoint`, `@Prompt`, and
`StreamingSession` for building real-time AI chat applications with token-by-token
streaming.

### Observability

Built-in Micrometer metrics and OpenTelemetry tracing (optional dependencies in
`atmosphere-runtime`). The Spring Boot starter integrates automatically with
Spring Boot Actuator.

### Redis and Kafka Clustering

First-party modules for clustering Atmosphere across multiple server instances.

---

## 9. Testing

### Test Framework

- **Core module (`atmosphere-runtime`):** Tests use **TestNG**. This is unchanged from 2.x/3.x.
- **Spring Boot starter:** Tests use **JUnit 5** via `spring-boot-starter-test`.
- **Quarkus extension:** Tests use **JUnit 5** via `quarkus-junit5`.
- For new application code, either TestNG or JUnit 5 works fine.

### Running Tests

```bash
# Run all tests
./mvnw test

# Run tests for a specific module
./mvnw test -pl modules/cpr

# Run a single test class
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest

# Run a single test method
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest#testBroadcast

# Run with debug output
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest -Dsurefire.useFile=false
```

### Surefire Configuration

The test suite requires `--add-opens` flags for concurrent lock access:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED
            --add-opens java.base/java.util.concurrent=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```

---

## 10. Troubleshooting

### ClassNotFoundException: javax.servlet.*

You have code or a dependency still referencing the old `javax.servlet` namespace.
Update all imports to `jakarta.servlet`. Check transitive dependencies with:

```bash
./mvnw dependency:tree | grep javax.servlet
```

### Virtual Thread Pinning Warnings

If you see warnings about virtual thread pinning (`VirtualThread ... pinned`),
check for `synchronized` blocks in your Atmosphere handlers that perform blocking
I/O. Replace them with `ReentrantLock`:

```java
// Before (pins virtual thread)
synchronized (lock) {
    blockingOperation();
}

// After (virtual-thread friendly)
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    blockingOperation();
} finally {
    lock.unlock();
}
```

### Quarkus: Servlet Not Initialized

If the Atmosphere servlet is not starting in Quarkus, verify that
`quarkus.atmosphere.load-on-startup` is greater than 0:

```properties
quarkus.atmosphere.load-on-startup=1
```

Quarkus skips `setLoadOnStartup` when the value is 0 or negative, unlike the
standard Servlet spec where 0 means "load on startup".

### Spring Boot: Annotations Not Detected

If your `@ManagedService` classes are not being discovered, make sure the
`atmosphere.packages` property includes the package containing your annotated
classes:

```yaml
atmosphere:
  packages: com.yourapp.atmosphere
```

The Spring Boot auto-configuration uses Spring's classpath scanner (since embedded
containers do not process `@HandlesTypes` from `ServletContainerInitializer`).

### JSONP Transport Removed

The JSONP transport has been removed in atmosphere.js 5.0. If your application
relied on JSONP, switch to long-polling or SSE, which work across all modern
browsers without JSONP.

### Old atmosphere-javascript 2.x / jQuery

The old jQuery-based `atmosphere.js` (and `jquery.atmosphere.js`) is not compatible
with Atmosphere 4.0 server. You must upgrade to `atmosphere.js` 5.0 (the npm
package). If you need to keep a jQuery-based frontend during migration, you can
proxy the old client through a compatibility layer, but this is not officially
supported.

---

## Quick Migration Checklist

- [ ] Upgrade JDK to 21+
- [ ] Upgrade server container (Tomcat 11, Jetty 12, etc.)
- [ ] Update `atmosphere-runtime` version to `4.0.0`
- [ ] Replace `javax.servlet-api` with `jakarta.servlet-api`
- [ ] Replace `javax.websocket-api` with `jakarta.websocket-api`
- [ ] Find and replace all `javax.servlet` imports with `jakarta.servlet`
- [ ] Find and replace all `javax.websocket` imports with `jakarta.websocket`
- [ ] Update `web.xml` namespace (or switch to Spring Boot / Quarkus config)
- [ ] Replace `atmosphere-javascript` / jQuery client with `atmosphere.js` 5.0 npm package
- [ ] Update client-side code to new subscribe/handlers API
- [ ] Replace any removed clustering plugins (JGroups, JMS, etc.) with Redis or Kafka modules
- [ ] Test with virtual threads enabled (default) and check for pinning issues
- [ ] Run full test suite: `./mvnw install`
