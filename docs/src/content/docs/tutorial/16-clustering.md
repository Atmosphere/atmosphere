---
title: "Chapter 16: Clustering with Redis and Kafka"
description: "Scale Atmosphere across multiple JVM instances using Redis pub/sub or Apache Kafka for cross-node message delivery, with echo prevention and topic management."
sidebar:
  order: 16
---

A single Atmosphere server can handle thousands of concurrent connections. But when you deploy behind a load balancer with multiple instances, a message broadcast on Node A needs to reach clients connected to Node B. This chapter covers two clustering backends -- Redis (pub/sub) and Kafka (topics) -- plus the trade-offs between them.

## Why Clustering Is Needed

Consider a chat application deployed to three nodes behind a load balancer:

```
                   +-------------+
                   |    Load     |
                   |  Balancer   |
                   +------+------+
              +-----------+-----------+
              v           v           v
         +---------+ +---------+ +---------+
         | Node A  | | Node B  | | Node C  |
         | 500     | | 500     | | 500     |
         | clients | | clients | | clients |
         +---------+ +---------+ +---------+
```

When a client connected to Node A sends a message, `Broadcaster.broadcast()` delivers it to all 500 clients on Node A. But the 1000 clients on Nodes B and C see nothing. Clustering solves this by relaying broadcast messages across nodes.

Both `RedisBroadcaster` and `KafkaBroadcaster` extend `DefaultBroadcaster` and override the `broadcast()` method to:

1. Publish the message to the external system (Redis channel or Kafka topic)
2. Deliver locally via `super.broadcast()`
3. On the receiving side, consume messages from other nodes and deliver locally

## Echo Prevention

A naive implementation would cause message echo: Node A broadcasts a message, publishes it to Redis, then receives its own message back from Redis and broadcasts it again. Both `RedisBroadcaster` and `KafkaBroadcaster` prevent this with a **node ID**:

- Each broadcaster instance generates a random UUID at startup (the node ID)
- Outgoing messages include the node ID (in the envelope for Redis, in a Kafka header named `atmosphere-node-id`)
- Incoming messages from the same node ID are silently dropped

This is completely automatic. You do not need to configure node IDs.

## Redis Setup

### Dependency

Add `atmosphere-redis` to your project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

This brings in [Lettuce](https://lettuce.io/), the non-blocking Redis client.

### RedisBroadcaster

`RedisBroadcaster` extends `DefaultBroadcaster` and uses Redis pub/sub to relay messages. It reads two configuration properties from `ApplicationConfig`:

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis URI (Lettuce format) |
| `org.atmosphere.redis.password` | (none) | Redis password (overrides URI password) |

**Spring Boot (`application.yml`):**

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.plugin.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
```

**Programmatic configuration:**

```java
framework.addInitParameter(ApplicationConfig.BROADCASTER_CLASS,
    "org.atmosphere.plugin.redis.RedisBroadcaster");
framework.addInitParameter("org.atmosphere.redis.url",
    "redis://localhost:6379");
```

Each `RedisBroadcaster` instance creates two Lettuce pub/sub connections (one for publishing, one for subscribing) and subscribes to a Redis channel named after the broadcaster ID. On `broadcast(msg)`, the message is serialized, wrapped in a `<nodeId>||<payload>` envelope, and published to the Redis channel in addition to being delivered locally.

### Redis URI Formats

Lettuce supports these URI formats:

```
redis://localhost:6379              # Plaintext
redis://password@localhost:6379     # With password
rediss://localhost:6380             # TLS
redis-sentinel://host1,host2:26379 # Sentinel
```

## RedisClusterBroadcastFilter

If you prefer to keep the default `DefaultBroadcaster` and add clustering as a filter, use `RedisClusterBroadcastFilter`. This class implements the `ClusterBroadcastFilter` interface:

```java
public interface ClusterBroadcastFilter extends BroadcastFilterLifecycle {
    void setUri(String name);
    void setBroadcaster(Broadcaster bc);
    Broadcaster getBroadcaster();
}
```

The filter subscribes to a Redis channel matching the broadcaster ID, publishes each local broadcast to Redis, and delivers remote messages locally. Echo prevention uses the same node ID mechanism as `RedisBroadcaster`.

**Configuration via `application.yml`:**

```yaml
atmosphere:
  packages: com.example.chat
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
    org.atmosphere.cpr.broadcastFilterClasses: org.atmosphere.plugin.redis.RedisClusterBroadcastFilter
```

**Per-service filter (only some endpoints need clustering):**

```java
@ManagedService(
    path = "/clustered-chat",
    broadcasterFilters = { RedisClusterBroadcastFilter.class }
)
public class ClusteredChat {
    // ...
}
```

| Approach | Use when |
|----------|----------|
| `RedisBroadcaster` | All broadcasters should be clustered. Simpler configuration. |
| `RedisClusterBroadcastFilter` | Only some broadcasters need clustering, or you need a custom broadcaster base class. |

## Kafka Setup

### Dependency

Add `atmosphere-kafka` to your project:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

### KafkaBroadcaster

`KafkaBroadcaster` extends `DefaultBroadcaster` and uses Apache Kafka to relay messages across JVM instances.

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka bootstrap servers |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Prefix for topic names |
| `org.atmosphere.kafka.group.id` | `atmosphere-<UUID>` | Consumer group ID (auto-generated) |

**Spring Boot (`application.yml`):**

```yaml
atmosphere:
  packages: com.example.chat
  broadcaster-class: org.atmosphere.kafka.KafkaBroadcaster
  init-params:
    org.atmosphere.kafka.bootstrap.servers: localhost:9092
    org.atmosphere.kafka.topic.prefix: "atmosphere."
```

Each `KafkaBroadcaster` instance:

1. Creates a Kafka producer (String key, byte[] value, acks=1)
2. Creates a Kafka consumer subscribed to a topic derived from the broadcaster ID
3. Starts a **virtual thread** that continuously polls for messages
4. On `broadcast(msg)`, serializes to `byte[]`, adds an `atmosphere-node-id` header, and publishes to the topic
5. On receiving a record from another node, delivers locally via `super.broadcast()`

### Topic Naming

Topic names are derived from the broadcaster ID with a configurable prefix. Characters that are not valid in Kafka topic names (anything except alphanumerics, dots, hyphens, and underscores) are replaced with underscores:

| Broadcaster ID | Prefix | Topic Name |
|---------------|--------|------------|
| `/atmosphere/chat` | `atmosphere.` | `atmosphere._atmosphere_chat` |
| `chat-room-1` | `atmosphere.` | `atmosphere.chat-room-1` |

### Consumer Group ID

By default, each `KafkaBroadcaster` instance generates a unique consumer group ID (`atmosphere-<UUID>`). This means every instance receives every message, which is the correct behavior for broadcast fan-out. If you set a shared `group.id`, Kafka's consumer group protocol would distribute messages across instances (each message goes to only one consumer), which is **not** what you want for broadcasting.

## Redis vs. Kafka

| Factor | Redis | Kafka |
|--------|-------|-------|
| **Latency** | Sub-millisecond pub/sub | Higher (batching, log commit) |
| **Message durability** | None (pub/sub is fire-and-forget) | Messages persisted to disk |
| **Delivery guarantee** | At-most-once | At-least-once (with idempotent producer) |
| **Scaling** | Single Redis or Sentinel | Partitioned, horizontally scalable |
| **Operational complexity** | Low (single process or cluster) | Higher (ZooKeeper/KRaft, brokers, topics) |
| **Missed messages** | Lost if subscriber is down | Retained in topic (configurable retention) |

Use **Redis** for most real-time applications (chat, notifications, dashboards). Use **Kafka** when you need message durability, replay capability, or are already running Kafka for event streaming.

## Works with Rooms

Clustering is transparent to the Room API. Because Rooms are backed by Broadcasters, replacing `DefaultBroadcaster` with `KafkaBroadcaster` or `RedisBroadcaster` automatically clusters your rooms:

```yaml
atmosphere:
  broadcaster-class: org.atmosphere.plugin.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: "redis://redis-host:6379"
```

```java
@RoomService(path = "/chat/{roomId}", maxHistory = 50)
public class ChatRoom {
    @Message
    public String onMessage(String message) {
        return message; // broadcast across all nodes automatically
    }
}
```

## Sticky Sessions

Both clustering solutions work with or without sticky sessions. However, **sticky sessions are recommended** for WebSocket connections to avoid reconnection storms when a load balancer reassigns a client to a different node.

Configure your load balancer to route by:
- **WebSocket:** connection-level affinity (most load balancers do this automatically)
- **Long-polling:** cookie or IP-based session affinity

## Testing Clustering Locally

Start Redis or Kafka via Docker and run two application instances on different ports:

```bash
# Redis
docker run -d --name redis -p 6379:6379 redis:7

# Terminal 1
SERVER_PORT=8080 ./mvnw spring-boot:run

# Terminal 2
SERVER_PORT=8081 ./mvnw spring-boot:run
```

Connect a browser to `http://localhost:8080` and another to `http://localhost:8081`. Send a message from one -- it should appear on both.

For Kafka, start a single-node KRaft broker:

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

## No Code Changes Required

The `@ManagedService` annotation does not change when clustering is enabled. Clustering is purely a configuration concern:

```java
@ManagedService(path = "/atmosphere/chat")
public class Chat {

    @Inject
    private AtmosphereResource r;

    @Ready
    public void onReady() {
        // This client is on one of N nodes.
        // Messages from any node will be relayed via Redis or Kafka.
    }

    @Message(encoders = JacksonEncoder.class,
             decoders = JacksonDecoder.class)
    public ChatMessage onMessage(ChatMessage msg) {
        // Returning the message broadcasts it.
        // The clustering broadcaster publishes externally AND delivers locally.
        return msg;
    }
}
```

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

```java
@Bean
public SessionStore sessionStore() {
    return new RedisSessionStore("redis://localhost:6379");
}
```

## Production Considerations

**Redis:**
- Use Redis Sentinel or Redis Cluster for high availability
- Each broadcaster creates two connections (pub + sub); plan your `maxclients` accordingly
- Monitor Redis pub/sub clients with `CLIENT LIST`

**Kafka:**
- Pre-create topics with appropriate partition count and replication factor
- Set `retention.ms` to a short value for real-time topics to avoid unbounded growth
- The consumer runs on a virtual thread with 500ms poll timeout

**Both:**
- Use sticky sessions at the load balancer for WebSocket connections
- Test failover: kill a node and verify that clients reconnect to surviving nodes

## Summary

- **`RedisBroadcaster`** relays messages via Redis pub/sub channels named after broadcaster IDs
- **`KafkaBroadcaster`** relays messages via Kafka topics with a configurable prefix, consuming on virtual threads
- **`RedisClusterBroadcastFilter`** provides filter-based clustering as an alternative to `RedisBroadcaster`
- **Echo prevention** is automatic via unique node IDs
- **No code changes** are needed -- clustering is a deployment configuration concern
- **Redis** is best for low-latency real-time apps; **Kafka** is best when you need durability and replay

Next up: [Chapter 17: Durable Sessions](/docs/tutorial/17-durable-sessions/) covers how to survive server restarts without losing client state.
