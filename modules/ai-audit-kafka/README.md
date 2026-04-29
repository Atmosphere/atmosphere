# atmosphere-ai-audit-kafka

`AuditSink` implementation that publishes Atmosphere governance audit
records to a Kafka topic as JSON. Pairs with the
[governance policy plane](../../docs/governance-policy-plane.md) — every
`GovernancePolicy.evaluate()` decision (admit / transform / deny / error)
fans out to registered sinks while the in-memory ring buffer keeps
serving the admin console.

## Install

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-audit-kafka</artifactId>
    <version>4.0.41</version>
</dependency>
```

## Usage

```java
import org.atmosphere.ai.audit.kafka.KafkaAuditSink;
import org.atmosphere.ai.governance.AsyncAuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;

var kafka = new KafkaAuditSink(
        "broker-1:9092,broker-2:9092",
        "atmosphere.governance.audit");

// Wrap with AsyncAuditSink so the admission thread never blocks
// on Kafka — bounded drop-on-full queue (Backpressure invariant).
GovernanceDecisionLog.installed().addSink(new AsyncAuditSink(kafka));
```

## Producer defaults

| Setting | Default | Why |
|---|---|---|
| `acks` | `1` | leader-ack only; audit loss on broker crash is acceptable |
| `linger.ms` | `50` | small batches, low latency |
| `batch.size` | `64 KB` | balanced throughput |
| key/value serializer | `StringSerializer` | JSON payload is a string |

Override via the three-arg constructor:

```java
new KafkaAuditSink(brokers, topic, Map.of(
    ProducerConfig.ACKS_CONFIG, "all",
    ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd"
));
```

## JSON shape

Records share the schema produced by `AuditJsonEncoder`, matching
Microsoft Agent Governance Toolkit's `audit_entry` so a downstream SIEM
consumer of either system reads both interchangeably.

```json
{
  "timestamp":      "2026-04-24T15:30:42.123Z",
  "policy_name":    "deny-destructive-sql",
  "policy_source":  "yaml:/etc/atmosphere/policies.yaml",
  "policy_version": "2026-04-21",
  "decision":       "deny",
  "reason":         "rule matched: action=destroy_data",
  "evaluation_ms":  0.42,
  "context_snapshot": { "tool_name": "drop_database", "user_id": "alice" }
}
```

## Operational notes

- **Ownership:** when constructed with broker connection params the sink
  owns the `KafkaProducer` and closes it in `close()`. When constructed
  with a pre-built `Producer` the caller retains ownership.
- **Shutdown:** call `kafka.close()` (or `AsyncAuditSink.close()` if
  wrapped) before JVM exit to flush in-flight batches.
- **Failure isolation:** sink failures never propagate to the policy
  pipeline — a stuck broker yields warn-level logs and the in-memory
  ring buffer remains authoritative.

## See also

- [Governance policy plane reference](../../docs/governance-policy-plane.md)
- Sister sink: `atmosphere-ai-audit-postgres` (JDBC writer)
