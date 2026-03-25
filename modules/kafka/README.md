# Atmosphere Kafka Clustering

Cross-node broadcasting via Kafka. Messages broadcast on one node are delivered to clients on all nodes.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-kafka</artifactId>
    <version>4.0.26</version>
</dependency>
```

## Quick Start

```properties
org.atmosphere.cpr.broadcasterClass=org.atmosphere.kafka.KafkaBroadcaster
org.atmosphere.kafka.bootstrap.servers=localhost:9092
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `org.atmosphere.kafka.bootstrap.servers` | `localhost:9092` | Kafka broker(s) |
| `org.atmosphere.kafka.topic.prefix` | `atmosphere.` | Topic name prefix |
| `org.atmosphere.kafka.group.id` | auto-generated | Consumer group ID |

## Key Classes

| Class | Purpose |
|-------|---------|
| `KafkaBroadcaster` | Broadcaster that publishes/consumes via Kafka topics |

## Full Documentation

See [docs/kafka.md](../../docs/kafka.md) for complete documentation.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- Kafka Clients 3.9+
