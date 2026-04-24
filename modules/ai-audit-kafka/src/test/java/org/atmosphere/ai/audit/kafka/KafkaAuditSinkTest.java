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

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.atmosphere.ai.governance.AuditEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link KafkaAuditSink} wire behavior using Kafka's in-process
 * {@link MockProducer} — no broker required.
 */
class KafkaAuditSinkTest {

    @Test
    void writeSerializesEntryAsJsonAndPublishes() {
        var producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        var sink = new KafkaAuditSink(producer, "governance.audit");

        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "scope::support",
                "code:test",
                "1.0",
                "deny",
                "message matched hijacking probe",
                Map.of("phase", "pre_admission", "message", "write python code"),
                0.42);
        sink.write(entry);

        assertEquals(1, producer.history().size(),
                "MockProducer must see one record: " + producer.history());
        var record = producer.history().get(0);
        assertEquals("governance.audit", record.topic());
        assertEquals("scope::support", record.key(),
                "key is policy name for partition stickiness");
        var json = record.value();
        assertNotNull(json);
        assertTrue(json.contains("\"decision\":\"deny\""),
                "json includes decision: " + json);
        assertTrue(json.contains("\"policy_name\":\"scope::support\""),
                "json includes policy_name: " + json);
        assertTrue(json.contains("\"evaluation_ms\":0.42"),
                "json includes evaluation_ms: " + json);
        assertTrue(json.contains("\"phase\":\"pre_admission\""),
                "context snapshot flattened: " + json);
    }

    @Test
    void externallyOwnedProducerIsNotClosedByClose() {
        var producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        var sink = new KafkaAuditSink(producer, "governance.audit");
        sink.close();
        // If sink owned the producer, close() would have closed it too; a
        // second write() would throw. External ownership means writes still
        // work after the sink close (Correctness Invariant #1).
        sink.write(sampleEntry());
        assertEquals(1, producer.history().size(),
                "producer stays open because ownership remains with the caller");
    }

    @Test
    void blankTopicRejected() {
        var producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        assertThrows(IllegalArgumentException.class,
                () -> new KafkaAuditSink(producer, ""));
    }

    @Test
    void nullProducerRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new KafkaAuditSink((org.apache.kafka.clients.producer.Producer<String, String>) null,
                        "t"));
    }

    @Test
    void jsonEscapesSpecialCharacters() {
        var producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        var sink = new KafkaAuditSink(producer, "governance.audit");

        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "policy", "src", "1.0",
                "deny",
                "quote \" and newline \n inside",
                Map.of(),
                0.0);
        sink.write(entry);

        var json = producer.history().get(0).value();
        assertTrue(json.contains("quote \\\" and newline \\n inside"),
                "special chars must be escaped: " + json);
    }

    private static AuditEntry sampleEntry() {
        return new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "policy", "src", "1.0", "admit", "", Map.of(), 0.0);
    }
}
