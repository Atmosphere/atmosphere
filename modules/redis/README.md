# Atmosphere Redis Clustering

Cross-node broadcasting via Redis pub/sub. Messages broadcast on one node are delivered to clients on all nodes.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-redis</artifactId>
    <version>4.0.35</version>
</dependency>
```

## Quick Start

```properties
org.atmosphere.cpr.broadcasterClass=org.atmosphere.redis.RedisBroadcaster
org.atmosphere.redis.url=redis://localhost:6379
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.redis.url` | `redis://localhost:6379` | Redis connection URL |
| `org.atmosphere.redis.password` | (none) | Optional password |

## Key Classes

| Class | Purpose |
|-------|---------|
| `RedisBroadcaster` | Broadcaster that publishes/subscribes via Redis pub/sub |
| `RedisClusterBroadcastFilter` | `ClusterBroadcastFilter` for use with `DefaultBroadcaster` |

## Full Documentation

See [docs/redis.md](../../docs/redis.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- Lettuce (Redis client)
