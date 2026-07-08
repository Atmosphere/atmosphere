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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the governance decision-log audit-sink configuration so operators can
 * point the decision log at an external durable store (Postgres, Kafka) for
 * long-term retention and compliance queries.
 *
 * <p><b>Default OFF.</b> No sink is installed unless the operator supplies a
 * connection target — {@code atmosphere.ai.governance.audit.postgres.url} for
 * the JDBC/Postgres sink, or
 * {@code atmosphere.ai.governance.audit.kafka.bootstrap-servers} for the Kafka
 * sink. Absent those, the decision log keeps only the in-process ring buffer
 * (Correctness Invariant #6: no external side effect without explicit opt-in).</p>
 *
 * <h2>Backpressure ({@code async}, default {@code true})</h2>
 * The concrete sinks perform blocking IO (JDBC insert, Kafka completion
 * bookkeeping) on the admission thread. Wrapping them in
 * {@link org.atmosphere.ai.governance.AsyncAuditSink} moves that IO to a
 * bounded background queue that drops-and-counts on overload rather than
 * blocking admission (Correctness Invariant #3). Set {@code async=false} only
 * when synchronous, in-line persistence is required and the store is known to
 * be fast (e.g. tests, an in-process H2).
 */
@ConfigurationProperties(prefix = "atmosphere.ai.governance.audit")
public class AtmosphereGovernanceAuditProperties {

    /**
     * Wrap the concrete sink in {@link org.atmosphere.ai.governance.AsyncAuditSink}
     * so admission threads never block on persistence IO. Default {@code true}.
     */
    private boolean async = true;

    /**
     * Bounded queue capacity for the async wrapper. On overload the wrapper
     * drops-and-counts (never blocks, never grows unbounded). Ignored when
     * {@link #async} is {@code false}.
     */
    private int queueCapacity = 1000;

    @NestedConfigurationProperty
    private final Postgres postgres = new Postgres();

    @NestedConfigurationProperty
    private final Kafka kafka = new Kafka();

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Postgres getPostgres() {
        return postgres;
    }

    public Kafka getKafka() {
        return kafka;
    }

    /** JDBC/Postgres decision-log sink. Activated by {@link #url} being set. */
    public static class Postgres {

        /**
         * JDBC URL of the audit store. When {@code null}/blank the Postgres sink
         * is not installed (feature OFF). Any JDBC URL works — the sink is tested
         * against H2 and targets Postgres in production.
         */
        private String url;

        /** JDBC username; may be {@code null} for URL-embedded credentials. */
        private String username;

        /** JDBC password; may be {@code null} for URL-embedded credentials. */
        private String password;

        /** Table to insert decisions into. Must be a simple SQL identifier. */
        private String table = "governance_audit_log";

        /**
         * Run {@code CREATE TABLE IF NOT EXISTS} on startup. Set {@code false}
         * when DDL is owned by Flyway/Liquibase/platform migrations.
         */
        private boolean autoCreate = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public boolean isAutoCreate() {
            return autoCreate;
        }

        public void setAutoCreate(boolean autoCreate) {
            this.autoCreate = autoCreate;
        }
    }

    /** Kafka decision-log sink. Activated by {@link #bootstrapServers} being set. */
    public static class Kafka {

        /**
         * Kafka bootstrap servers. When {@code null}/blank the Kafka sink is not
         * installed (feature OFF).
         */
        private String bootstrapServers;

        /** Topic decisions are published to as JSON. */
        private String topic = "atmosphere.governance.audit";

        /** Extra producer properties merged after the sink's fire-and-forget defaults. */
        private final Map<String, String> properties = new LinkedHashMap<>();

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public Map<String, String> getProperties() {
            return properties;
        }
    }
}
