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
package org.atmosphere.spring.boot;

import java.time.Instant;
import java.util.Map;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery test for the {@code ai-audit-kafka} half of the blog's "the decision
 * log can be pointed at Kafka or Postgres; every admit and deny persisted"
 * claim.
 *
 * <p>Supplies a {@link MockProducer} bean (the caller-owned-producer seam the
 * auto-config uses for mTLS/SASL in production), drives a real admit AND a real
 * deny through the installed {@link GovernanceDecisionLog}, then asserts BOTH
 * were produced to the topic — an observable side effect, not merely that the
 * sink type exists.</p>
 */
class AtmosphereGovernanceAuditKafkaAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereGovernanceAuditKafkaAutoConfiguration.class))
            .withUserConfiguration(MockProducerConfig.class)
            .withPropertyValues(
                    "atmosphere.ai.governance.audit.kafka.bootstrap-servers=localhost:9092",
                    "atmosphere.ai.governance.audit.kafka.topic=governance.audit",
                    // synchronous so the record is produced the moment record() returns
                    "atmosphere.ai.governance.audit.async=false");

    @BeforeEach
    @AfterEach
    void resetLog() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void producesAdmitAndDenyThroughTheDecisionLog() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            var producer = context.getBean(MockProducerConfig.class).mock();
            var log = GovernanceDecisionLog.installed();
            assertThat(log.sinks())
                    .as("the Kafka audit sink must be registered")
                    .anyMatch(s -> s.name().equals("kafka:governance.audit"));

            log.record(entry("admit", ""));
            log.record(entry("deny", "matched exfiltration probe"));

            var history = producer.history();
            assertThat(history)
                    .as("both the admit and the deny must be produced to Kafka")
                    .hasSize(2);
            assertThat(history.stream().map(ProducerRecord::topic).distinct())
                    .containsExactly("governance.audit");
            assertThat(history.stream().map(ProducerRecord::key))
                    .as("both records keyed by policy name")
                    .containsExactly("scope::support", "scope::support");
        });
    }

    private static AuditEntry entry(String decision, String reason) {
        return new AuditEntry(
                Instant.now(),
                "scope::support",
                "code:test",
                "1.0",
                decision,
                reason,
                Map.of("phase", "pre_admission", "message", "hello"),
                0.42);
    }

    @Configuration(proxyBeanMethods = false)
    static class MockProducerConfig {

        // kafka-clients 4.x on the starter classpath dropped the
        // (boolean, Serializer, Serializer) constructor; use
        // (autoComplete, partitioner, keySer, valueSer). The default empty
        // cluster never invokes the (null) partitioner, and the string
        // serializers let send() serialize the JSON payload into history().
        private final MockProducer<String, String> producer =
                new MockProducer<String, String>(
                        true, null, new StringSerializer(), new StringSerializer());

        @Bean
        Producer<String, String> auditProducer() {
            return producer;
        }

        MockProducer<String, String> mock() {
            return producer;
        }
    }
}
