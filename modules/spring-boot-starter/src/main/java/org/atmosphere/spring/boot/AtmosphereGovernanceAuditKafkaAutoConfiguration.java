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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.Producer;
import org.atmosphere.ai.audit.kafka.KafkaAuditSink;
import org.atmosphere.ai.governance.AsyncAuditSink;
import org.atmosphere.ai.governance.AuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Points the governance {@link GovernanceDecisionLog} at Kafka so every admit
 * and deny is published to a topic for long-term retention and downstream
 * SIEM/streaming pipelines — the {@code ai-audit-kafka} half of the blog's
 * "the decision log can be pointed at Kafka or Postgres" claim.
 *
 * <p><b>Default OFF (Correctness Invariant #6).</b> Inert unless BOTH the
 * {@code atmosphere-ai-audit-kafka} module is on the classpath
 * ({@link KafkaAuditSink}) AND
 * {@code atmosphere.ai.governance.audit.kafka.bootstrap-servers} is set.</p>
 *
 * <p>The sink is wrapped in {@link AsyncAuditSink} by default so a slow broker
 * never back-pressures the admission thread (Correctness Invariant #3); set
 * {@code atmosphere.ai.governance.audit.async=false} to publish in-line.</p>
 *
 * <p>Operators needing mTLS/SASL or a custom serde expose their own
 * {@link Producer Producer&lt;String, String&gt;} bean; when present it is used
 * verbatim and the sink does NOT own it (Correctness Invariant #1) — the caller
 * retains lifecycle. Absent a bean, a fire-and-forget producer is built from
 * {@code bootstrap-servers} and IS owned/closed here.</p>
 *
 * <p>Ownership (Correctness Invariant #1): the wrapper and sink are removed and
 * closed on shutdown; a self-built producer's IO threads are released on
 * context teardown, an injected producer is left untouched.</p>
 */
@AutoConfiguration(after = AtmosphereAdminAutoConfiguration.class)
@ConditionalOnClass({KafkaAuditSink.class, GovernanceDecisionLog.class})
@ConditionalOnProperty(prefix = "atmosphere.ai.governance.audit.kafka", name = "bootstrap-servers")
@EnableConfigurationProperties(AtmosphereGovernanceAuditProperties.class)
public class AtmosphereGovernanceAuditKafkaAutoConfiguration
        implements InitializingBean, DisposableBean {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereGovernanceAuditKafkaAutoConfiguration.class);

    private final AtmosphereGovernanceAuditProperties properties;
    private final ObjectProvider<Producer<String, String>> producerProvider;
    private AuditSink installedSink;

    public AtmosphereGovernanceAuditKafkaAutoConfiguration(
            AtmosphereGovernanceAuditProperties properties,
            ObjectProvider<Producer<String, String>> producerProvider) {
        this.properties = properties;
        this.producerProvider = producerProvider;
    }

    @Override
    public void afterPropertiesSet() {
        var kafka = properties.getKafka();
        var producer = producerProvider.getIfAvailable();
        AuditSink sink;
        if (producer != null) {
            // Caller-owned producer (mTLS/SASL/custom serde); the sink does not close it.
            sink = new KafkaAuditSink(producer, kafka.getTopic());
        } else {
            Map<String, Object> extra = new LinkedHashMap<>(kafka.getProperties());
            sink = new KafkaAuditSink(kafka.getBootstrapServers(), kafka.getTopic(), extra);
        }
        if (properties.isAsync()) {
            sink = new AsyncAuditSink(sink, properties.getQueueCapacity());
        }
        // Fan-out only fires on a non-NOOP log; install over the NOOP if nothing
        // else has so persistence works without the admin module. Never clobber
        // an operator's / admin's own install(...).
        if (GovernanceDecisionLog.installed().capacity() == 0) {
            GovernanceDecisionLog.install(GovernanceDecisionLog.DEFAULT_CAPACITY);
        }
        GovernanceDecisionLog.installed().addSink(sink);
        this.installedSink = sink;
        logger.info("Governance decision log pointed at Kafka audit store: topic={}, async={} "
                + "(sink: {})", kafka.getTopic(), properties.isAsync(), sink.name());
    }

    @Override
    public void destroy() {
        if (installedSink != null) {
            GovernanceDecisionLog.installed().removeSink(installedSink);
            installedSink.close();
            installedSink = null;
        }
    }
}
