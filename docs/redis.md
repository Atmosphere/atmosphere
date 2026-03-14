# Redis Clustering

Cross-node broadcasting via Redis pub/sub. Messages broadcast on one node are delivered to clients on all nodes.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.14</version>
</dependency>
```

## Quick Start

Configure the broadcaster class and Redis connection:

```properties
org.atmosphere.cpr.broadcasterClass=org.atmosphere.redis.RedisBroadcaster
org.atmosphere.redis.url=redis://localhost:6379
```

Or use `RedisClusterBroadcastFilter` with the default broadcaster:

```properties
org.atmosphere.cpr.broadcastFilterClasses=org.atmosphere.redis.RedisClusterBroadcastFilter
org.atmosphere.redis.url=redis://localhost:6379
```

### Spring Boot

```yaml
atmosphere:
  broadcaster-class: org.atmosphere.redis.RedisBroadcaster
  init-params:
    org.atmosphere.redis.url: redis://localhost:6379
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis connection URL |
| `org.atmosphere.redis.password` | (none) | Optional password |

## How It Works

`RedisBroadcaster` extends `DefaultBroadcaster` and publishes every broadcast message to a Redis channel. Each node subscribes to the same channel and delivers incoming messages to its local clients. A node ID header prevents echo (re-broadcasting messages that originated locally).

## Key Classes

| Class | Purpose |
|-------|---------|
| `RedisBroadcaster` | Broadcaster that publishes/subscribes via Redis pub/sub |
| `RedisClusterBroadcastFilter` | `ClusterBroadcastFilter` for use with `DefaultBroadcaster` |

## See Also

- [Kafka Clustering](kafka.md)
- [Core Runtime](core.md) -- Broadcaster API
