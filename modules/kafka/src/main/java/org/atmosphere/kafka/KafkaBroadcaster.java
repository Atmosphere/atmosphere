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
package org.atmosphere.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Broadcaster} that uses Apache Kafka to relay broadcast messages across JVM instances.
 * <p>
 * Messages published locally are produced to a Kafka topic derived from the Broadcaster ID
 * (with a configurable prefix). Messages consumed from other nodes are delivered locally
 * via {@link DefaultBroadcaster#broadcast(Object)}.
 * A unique node ID header prevents message echo (re-broadcasting messages that originated locally).
 * <p>
 * Configuration (via {@link org.atmosphere.cpr.ApplicationConfig}):
 * <ul>
 *   <li>{@code org.atmosphere.kafka.bootstrap.servers} — Kafka bootstrap servers (default: {@code localhost:9092})</li>
 *   <li>{@code org.atmosphere.kafka.topic.prefix} — Topic name prefix (default: {@code atmosphere.})</li>
 *   <li>{@code org.atmosphere.kafka.group.id} — Consumer group ID (default: auto-generated UUID)</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public class KafkaBroadcaster extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(KafkaBroadcaster.class);
    private static final String NODE_ID_HEADER = "atmosphere-node-id";

    public static final String KAFKA_BOOTSTRAP_SERVERS = "org.atmosphere.kafka.bootstrap.servers";
    public static final String KAFKA_TOPIC_PREFIX = "org.atmosphere.kafka.topic.prefix";
    public static final String KAFKA_GROUP_ID = "org.atmosphere.kafka.group.id";

    private final String nodeId = UUID.randomUUID().toString();
    private final AtomicBoolean consuming = new AtomicBoolean(false);

    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<String, byte[]> consumer;
    private String topicName;
    private Thread consumerThread;

    public KafkaBroadcaster() {
    }

    @Override
    public Broadcaster initialize(String name, URI uri, AtmosphereConfig config) {
        super.initialize(name, uri, config);

        var bootstrapServers = config.getInitParameter(KAFKA_BOOTSTRAP_SERVERS, "localhost:9092");
        var topicPrefix = config.getInitParameter(KAFKA_TOPIC_PREFIX, "atmosphere.");
        var groupId = config.getInitParameter(KAFKA_GROUP_ID, "atmosphere-" + UUID.randomUUID());

        topicName = topicPrefix + sanitizeTopicName(getID());

        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "1"
        ));

        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true"
        ));

        consumer.subscribe(List.of(topicName));
        consuming.set(true);

        consumerThread = Thread.ofVirtual()
                .name("atmosphere-kafka-consumer-" + getID())
                .start(this::consumeLoop);

        logger.info("KafkaBroadcaster {} subscribed to Kafka topic '{}'", nodeId, topicName);

        return this;
    }

    @Override
    public Future<Object> broadcast(Object msg) {
        publishToKafka(msg);
        return super.broadcast(msg);
    }

    @Override
    public void releaseExternalResources() {
        consuming.set(false);

        if (consumerThread != null) {
            consumerThread.interrupt();
        }

        try {
            if (consumer != null) {
                consumer.wakeup();
                consumer.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            logger.trace("Error closing Kafka consumer", e);
        }
        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            logger.trace("Error closing Kafka producer", e);
        }
        logger.info("KafkaBroadcaster {} released Kafka resources for topic '{}'", nodeId, topicName);
    }

    private void publishToKafka(Object msg) {
        try {
            var payload = serializeMessage(msg);
            var record = new ProducerRecord<>(topicName, getID(), payload);
            record.headers().add(NODE_ID_HEADER, nodeId.getBytes(StandardCharsets.UTF_8));
            producer.send(record);
        } catch (Exception e) {
            logger.warn("Failed to publish message to Kafka topic '{}'", topicName, e);
        }
    }

    private void consumeLoop() {
        try {
            while (consuming.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                    for (var record : records) {
                        var senderNodeId = extractNodeId(record.headers());
                        if (nodeId.equals(senderNodeId)) {
                            continue;
                        }

                        var message = new String(record.value(), StandardCharsets.UTF_8);
                        logger.trace("Received remote broadcast on topic '{}' from node {}", topicName, senderNodeId);
                        super.broadcast(message);
                    }
                } catch (org.apache.kafka.common.errors.WakeupException e) {
                    if (!consuming.get()) break;
                } catch (Exception e) {
                    if (consuming.get()) {
                        logger.warn("Error polling Kafka topic '{}', retrying...", topicName, e);
                    }
                }
            }
        } finally {
            logger.trace("Kafka consumer loop terminated for topic '{}'", topicName);
        }
    }

    private String extractNodeId(org.apache.kafka.common.header.Headers headers) {
        Header header = headers.lastHeader(NODE_ID_HEADER);
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private byte[] serializeMessage(Object msg) {
        return switch (msg) {
            case byte[] bytes -> bytes;
            case String s -> s.getBytes(StandardCharsets.UTF_8);
            default -> msg.toString().getBytes(StandardCharsets.UTF_8);
        };
    }

    /**
     * Sanitize broadcaster ID to be a valid Kafka topic name.
     * Kafka topics allow alphanumerics, dots, hyphens, and underscores.
     */
    private String sanitizeTopicName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
