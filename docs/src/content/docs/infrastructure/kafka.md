---
title: "Kafka Clustering"
description: "Cross-node broadcasting via Kafka"
---

# Kafka Clustering

Cross-node broadcasting via Kafka. Messages broadcast on one node are delivered to clients on all nodes.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.11-SNAPSHOT</version>
</dependency>
```

## Quick Start

Configure the broadcaster class and Kafka connection:

```properties
org.atmosphere.cpr.broadcasterClass=org.atmosphere.kafka.KafkaBroadcaster
org.atmosphere.kafka.bootstrap.servers=localhost:9092
```

### Spring Boot

```yaml
atmosphere:
  broadcaster-class: org.atmosphere.kafka.KafkaBroadcaster
  init-params:
    org.atmosphere.kafka.bootstrap.servers: localhost:9092
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka broker(s) |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Topic name prefix |
| `org.atmosphere.kafka.group.id` | auto-generated | Consumer group ID |

## How It Works

`KafkaBroadcaster` extends `DefaultBroadcaster` and publishes every broadcast message to a Kafka topic. Each node runs a consumer that polls for messages and delivers them to local clients. A node ID Kafka header (`atmosphere-node-id`) prevents echo.

Topic names are derived from the broadcaster ID: `<prefix><sanitized-broadcaster-id>`.

The consumer loop runs on a virtual thread.

## Key Classes

| Class | Purpose |
|-------|---------|
| `KafkaBroadcaster` | Broadcaster that publishes/consumes via Kafka topics |

## See Also

- [Redis Clustering](redis.md)
- [Core Runtime](core.md) -- Broadcaster API
