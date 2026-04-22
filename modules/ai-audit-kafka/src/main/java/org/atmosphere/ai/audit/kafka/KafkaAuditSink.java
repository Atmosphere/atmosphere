/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.audit.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.AuditJsonEncoder;
import org.atmosphere.ai.governance.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * {@link AuditSink} that publishes {@link AuditEntry} records to a Kafka
 * topic as JSON. Wrap with
 * {@link org.atmosphere.ai.governance.AsyncAuditSink} in production so the
 * admission thread is not blocked by Kafka's async completion bookkeeping
 * — this class's {@code write} call still awaits no network IO but
 * {@code send().get()} would; the Async wrapper gives us bounded-queue
 * backpressure when the broker is slow.
 *
 * <p>The Kafka producer is configured for fire-and-forget delivery by
 * default: {@code acks=1}, {@code linger.ms=50}, {@code batch.size=64KB}.
 * Operators that need {@code acks=all} or custom serde pass an already-
 * built {@link Producer} via the convenience constructor.</p>
 */
public final class KafkaAuditSink implements AuditSink {

    private static final Logger logger = LoggerFactory.getLogger(KafkaAuditSink.class);

    private final Producer<String, String> producer;
    private final String topic;
    private final boolean ownsProducer;

    /**
     * Build a sink backed by a freshly configured {@link KafkaProducer}. The
     * bootstrap servers / client-id / ack level are applied verbatim; the
     * serializers are forced to string so the JSON payload flows through.
     */
    public KafkaAuditSink(String bootstrapServers, String topic) {
        this(bootstrapServers, topic, Map.of());
    }

    /**
     * @param extraProps extra producer properties merged after defaults
     */
    public KafkaAuditSink(String bootstrapServers, String topic,
                          Map<String, Object> extraProps) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "atmosphere-audit");
        if (extraProps != null) {
            for (var entry : extraProps.entrySet()) {
                props.put(entry.getKey(), entry.getValue());
            }
        }
        this.producer = new KafkaProducer<>(props);
        this.topic = topic;
        this.ownsProducer = true;
    }

    /**
     * Use an externally-built {@link Producer} — gives operators full
     * control over serde, interceptors, security (mTLS, SASL). The sink
     * does NOT close the producer on its own {@link #close()} in this
     * mode; the caller retains ownership (Correctness Invariant #1).
     */
    public KafkaAuditSink(Producer<String, String> producer, String topic) {
        if (producer == null) {
            throw new IllegalArgumentException("producer must not be null");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.producer = producer;
        this.topic = topic;
        this.ownsProducer = false;
    }

    @Override
    public void write(AuditEntry entry) {
        var json = AuditJsonEncoder.encode(entry);
        var record = new ProducerRecord<>(topic, entry.policyName(), json);
        // send() returns a Future — we don't wait on it. Pair with
        // AsyncAuditSink in production so a slow broker doesn't back up
        // the admission path. Kafka producer retries transient failures
        // internally via delivery.timeout.ms.
        producer.send(record, (metadata, ex) -> {
            if (ex != null) {
                logger.warn("Kafka audit publish to topic '{}' failed: {}",
                        topic, ex.toString());
            }
        });
    }

    @Override
    public void close() {
        if (ownsProducer) {
            try {
                producer.flush();
                producer.close(java.time.Duration.ofSeconds(5));
            } catch (RuntimeException e) {
                logger.warn("Kafka producer close failed: {}", e.toString());
            }
        }
    }

    @Override
    public String name() {
        return "kafka:" + topic;
    }

    /** Best-effort flush — useful in tests that need to await delivery. */
    public void flush(java.time.Duration timeout) {
        if (timeout == null) {
            producer.flush();
            return;
        }
        producer.flush();
        try {
            TimeUnit.MILLISECONDS.sleep(Math.min(timeout.toMillis(), 50));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
