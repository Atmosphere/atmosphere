---
title: "Chapter 16: Clustering with Redis and Kafka"
description: "Scale Atmosphere across multiple JVM instances using Redis pub/sub or Apache Kafka for cross-node message delivery, with echo prevention and topic management."
---

A single Atmosphere server can handle thousands of concurrent connections. But when you deploy behind a load balancer with multiple instances, a message broadcast on Node A needs to reach clients connected to Node B. This chapter covers two clustering backends: Redis (pub/sub) and Kafka (topics), plus the trade-offs between them.

## Why Clustering Is Needed

Consider a chat application deployed to three nodes behind a load balancer:

```
                   ┌───────────┐
                   │   Load    │
                   │ Balancer  │
                   └─────┬─────┘
              ┌──────────┼──────────┐
              v          v          v
         ┌─────────┐ ┌─────────┐ ┌─────────┐
         │ Node A  │ │ Node B  │ │ Node C  │
         │ 500     │ │ 500     │ │ 500     │
         │ clients │ │ clients │ │ clients │
         └─────────┘ └─────────┘ └─────────┘
```

When a client connected to Node A sends a message, `Broadcaster.broadcast()` delivers it to all 500 clients on Node A. But the 1000 clients on Nodes B and C see nothing. Clustering solves this by relaying broadcast messages across nodes.

Both `RedisBroadcaster` and `KafkaBroadcaster` extend `DefaultBroadcaster` and override the `broadcast()` method to:

1. Publish the message to the external system (Redis channel or Kafka topic)
2. Deliver locally via `super.broadcast()`
3. On the receiving side, consume messages from other nodes and deliver locally

## Echo Prevention

A naive implementation would cause message echo: Node A broadcasts a message, publishes it to Redis, then receives its own message back from Redis and broadcasts it again. Both `RedisBroadcaster` and `KafkaBroadcaster` prevent this with a **node ID**:

- Each broadcaster instance generates a random UUID at startup (the node ID)
- Outgoing messages include the node ID (in the envelope for Redis, in a Kafka header)
- Incoming messages from the same node ID are silently dropped

This is completely automatic. You do not need to configure node IDs.

## Redis Setup

### Dependency

Add `atmosphere-redis` to your project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.10</version>
</dependency>
```

This brings in [Lettuce](https://lettuce.io/), the non-blocking Redis client.

### RedisBroadcaster Configuration

The simplest approach is to replace the default broadcaster class globally:

**Spring Boot (`application.yml`):**

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.plugin.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
    # Optional: Redis password
    # org.atmosphere.redis.password: mysecret
```

**Quarkus (`application.properties`):**

```properties
quarkus.atmosphere.packages=com.example.chat
quarkus.atmosphere.broadcaster-class=org.atmosphere.plugin.redis.RedisBroadcaster
quarkus.atmosphere.init-params.org.atmosphere.redis.url=redis://localhost:6379
```

**Programmatic configuration:**

```java
framework.addInitParameter(ApplicationConfig.BROADCASTER_CLASS,
    "org.atmosphere.plugin.redis.RedisBroadcaster");
framework.addInitParameter("org.atmosphere.redis.url",
    "redis://localhost:6379");
```

### How RedisBroadcaster Works

Each `RedisBroadcaster` instance:

1. Creates two Lettuce pub/sub connections (one for publishing, one for subscribing)
2. Subscribes to a Redis channel named after the broadcaster ID (e.g., `"/atmosphere/chat"`)
3. On `broadcast(msg)`:
   - Serializes the message to a string
   - Wraps it in an envelope: `<nodeId>||<payload>`
   - Publishes to the Redis channel
   - Delivers locally via `super.broadcast(msg)`
4. On receiving a Redis message:
   - Extracts the sender's node ID from before the `||` separator
   - Drops the message if the sender is this node
   - Otherwise, delivers locally via `super.broadcast(payload)`

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis URI (Lettuce format) |
| `org.atmosphere.redis.password` | (none) | Redis password (overrides URI password) |

### Redis URI Formats

Lettuce supports these URI formats:

```
redis://localhost:6379              # Plaintext
redis://password@localhost:6379     # With password
rediss://localhost:6380             # TLS
redis-sentinel://host1,host2:26379 # Sentinel
```

## RedisClusterBroadcastFilter: An Alternative Approach

If you prefer to keep the default `DefaultBroadcaster` and add clustering as a filter, use `RedisClusterBroadcastFilter`:

```yaml
atmosphere:
  packages: com.example.chat
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
    org.atmosphere.cpr.broadcastFilterClasses: org.atmosphere.plugin.redis.RedisClusterBroadcastFilter
```

The filter implements `ClusterBroadcastFilter` and works similarly to `RedisBroadcaster`:

- It subscribes to a Redis channel matching the broadcaster ID
- On each local broadcast, it publishes the message to Redis
- On receiving a remote message, it calls `broadcaster.broadcast()` to deliver locally
- Echo prevention uses the same node ID mechanism

**When to use the filter vs. the broadcaster:**

| Approach | Use when... |
|----------|------------|
| `RedisBroadcaster` | All broadcasters should be clustered. Simpler configuration. |
| `RedisClusterBroadcastFilter` | Only some broadcasters need clustering, or you need a custom broadcaster base class. |

The filter approach lets you mix clustered and non-clustered broadcasters in the same application by adding the filter only to specific broadcasters:

```java
@ManagedService(
    path = "/clustered-chat",
    broadcasterFilters = { RedisClusterBroadcastFilter.class }
)
public class ClusteredChat {
    // ...
}
```

## Kafka Setup

### Dependency

Add `atmosphere-kafka` to your project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.10</version>
</dependency>
```

This brings in the Apache Kafka client libraries.

### KafkaBroadcaster Configuration

**Spring Boot (`application.yml`):**

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.kafka.KafkaBroadcaster
  init-params:
    org.atmosphere.kafka.bootstrap.servers: localhost:9092
    # Optional: custom topic prefix (default: "atmosphere.")
    org.atmosphere.kafka.topic.prefix: "atmosphere."
    # Optional: consumer group ID (default: auto-generated UUID)
    # org.atmosphere.kafka.group.id: my-app-group
```

**Quarkus (`application.properties`):**

```properties
quarkus.atmosphere.packages=com.example.chat
quarkus.atmosphere.broadcaster-class=org.atmosphere.kafka.KafkaBroadcaster
quarkus.atmosphere.init-params.org.atmosphere.kafka.bootstrap.servers=localhost:9092
```

### How KafkaBroadcaster Works

Each `KafkaBroadcaster` instance:

1. Creates a Kafka producer (String key, byte[] value, acks=1)
2. Creates a Kafka consumer subscribed to a topic derived from the broadcaster ID
3. Starts a virtual thread that continuously polls for messages
4. On `broadcast(msg)`:
   - Serializes the message to `byte[]`
   - Creates a `ProducerRecord` with the broadcaster ID as key and `atmosphere-node-id` as a header
   - Publishes to the topic
   - Delivers locally via `super.broadcast(msg)`
5. On receiving a Kafka record:
   - Extracts the `atmosphere-node-id` header
   - Drops the record if the sender is this node
   - Otherwise, delivers locally via `super.broadcast(message)`

### Topic Naming

Topic names are derived from the broadcaster ID with a configurable prefix:

```
topic = prefix + sanitize(broadcasterID)
```

The sanitization replaces characters that are not valid in Kafka topic names (anything except alphanumerics, dots, hyphens, and underscores) with underscores.

Examples:

| Broadcaster ID | Prefix | Topic Name |
|---------------|--------|------------|
| `/atmosphere/chat` | `atmosphere.` | `atmosphere._atmosphere_chat` |
| `chat-room-1` | `atmosphere.` | `atmosphere.chat-room-1` |
| `room.general` | `myapp.` | `myapp.room.general` |

### Consumer Group ID

By default, each `KafkaBroadcaster` instance generates a unique consumer group ID (`atmosphere-<UUID>`). This means every instance receives every message, which is the correct behavior for broadcast fan-out.

If you set a shared `group.id`, Kafka's consumer group protocol would distribute messages across instances (each message goes to only one consumer), which is **not** what you want for broadcasting. Leave the default unless you have a specific reason to change it.

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka bootstrap servers |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Prefix for topic names |
| `org.atmosphere.kafka.group.id` | `atmosphere-<UUID>` | Consumer group ID (auto-generated) |

## Redis vs. Kafka: When to Use Which

| Factor | Redis | Kafka |
|--------|-------|-------|
| **Latency** | Sub-millisecond pub/sub | Higher (batching, log commit) |
| **Message durability** | None (pub/sub is fire-and-forget) | Messages persisted to disk |
| **Delivery guarantee** | At-most-once | At-least-once (with idempotent producer) |
| **Scaling** | Single Redis or Sentinel | Partitioned, horizontally scalable |
| **Operational complexity** | Low (single process or cluster) | Higher (ZooKeeper/KRaft, brokers, topics) |
| **Best for** | Low-latency chat, notifications | High-throughput event streaming, audit trails |
| **Missed messages** | Lost if subscriber is down | Retained in topic (configurable retention) |

**Recommendation:**

- Use **Redis** for most real-time applications (chat, notifications, dashboards). It is simpler to operate and has the lowest latency.
- Use **Kafka** when you need message durability, replay capability, or are already running Kafka for event streaming.

## Testing Clustering Locally

You can test clustering on a single machine by running multiple instances on different ports.

### With Redis

Start Redis:

```bash
docker run -d --name redis -p 6379:6379 redis:7
```

Start two Spring Boot instances:

```bash
# Terminal 1
SERVER_PORT=8080 ./mvnw spring-boot:run

# Terminal 2
SERVER_PORT=8081 ./mvnw spring-boot:run
```

Or with Quarkus:

```bash
# Terminal 1
./mvnw quarkus:dev -Dquarkus.http.port=8080

# Terminal 2
./mvnw quarkus:dev -Dquarkus.http.port=8081
```

Connect a browser to `http://localhost:8080` and another to `http://localhost:8081`. Send a message from one -- it should appear on both.

### With Kafka

Start Kafka (using the single-node KRaft mode):

```bash
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_CFG_NODE_ID=0 \
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@localhost:9093 \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  bitnami/kafka:3.7
```

The rest is the same -- run two application instances on different ports and verify cross-node delivery.

## Production Considerations

### Redis

- Use Redis Sentinel or Redis Cluster for high availability
- Configure connection pooling in Lettuce if you have many broadcasters
- Monitor Redis pub/sub clients with `CLIENT LIST`
- Each broadcaster creates two connections (pub + sub), so plan your `maxclients` accordingly

### Kafka

- Pre-create topics with appropriate partition count and replication factor
- Set `retention.ms` to a short value for real-time topics (e.g., 1 hour) to avoid unbounded growth
- Monitor consumer lag to detect slow nodes
- The consumer runs on a virtual thread with 500ms poll timeout

### Both

- Use sticky sessions at the load balancer for WebSocket connections (most load balancers do this by default for WebSocket upgrade requests)
- Monitor the `atmosphere.messages.sent` counter to verify cross-node delivery
- Test failover: kill a node and verify that clients reconnect to surviving nodes

## Combining with Durable Sessions

Clustering and durable sessions complement each other:

- **Clustering** ensures messages reach all nodes
- **Durable sessions** (covered in [Chapter 17](/docs/tutorial/17-durable-sessions/)) ensure clients can reconnect after a node failure and resume their room memberships

For a fully resilient setup, combine `RedisBroadcaster` (or `KafkaBroadcaster`) with `RedisSessionStore`:

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.plugin.redis.RedisBroadcaster
  durable-sessions:
    enabled: true
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
```

Then declare a `RedisSessionStore` bean that shares the same Redis instance:

```java
@Bean
public SessionStore sessionStore() {
    return new RedisSessionStore("redis://localhost:6379");
}
```

## Complete Spring Boot Example with Redis

`pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.10</version>
</dependency>
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.10</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

`application.yml`:

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.plugin.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
```

`Chat.java`:

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() {
        // This client is on one of N nodes.
        // Messages from any node will be relayed via Redis.
    }

    @Message(encoders = JacksonEncoder.class,
             decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage msg) {
        // Returning the message broadcasts it.
        // RedisBroadcaster publishes to Redis AND delivers locally.
        // Other nodes receive from Redis and deliver to their clients.
        return msg;
    }
}
```

No changes to your application code are needed -- the `@ManagedService` is identical to the single-node version. The clustering is entirely a configuration concern.

## Complete Quarkus Example with Kafka

`pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.10</version>
</dependency>
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.10</version>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-undertow</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-websockets</artifactId>
</dependency>
```

`application.properties`:

```properties
quarkus.atmosphere.packages=com.example.chat
quarkus.atmosphere.broadcaster-class=org.atmosphere.kafka.KafkaBroadcaster
quarkus.atmosphere.init-params.org.atmosphere.kafka.bootstrap.servers=localhost:9092
```

The `@ManagedService` class is identical to the Redis example above.

## Summary

- **`RedisBroadcaster`** relays messages via Redis pub/sub channels named after broadcaster IDs
- **`KafkaBroadcaster`** relays messages via Kafka topics with a configurable prefix
- **`RedisClusterBroadcastFilter`** provides filter-based clustering as an alternative to `RedisBroadcaster`
- **Echo prevention** is automatic via unique node IDs
- **No code changes** are needed -- clustering is a deployment configuration concern
- **Redis** is best for low-latency real-time apps; **Kafka** is best when you need durability and replay

Next up: [Chapter 17: Durable Sessions](/docs/tutorial/17-durable-sessions/) covers how to survive server restarts without losing client state.
