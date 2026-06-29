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
package org.atmosphere.samples.springboot.msgovernance;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.audit.kafka.KafkaAuditSink;
import org.atmosphere.ai.audit.postgres.JdbcAuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.PolicyAdmissionGate;
import org.atmosphere.ai.governance.TimeWindowPolicy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Atmosphere-4 blog §4 claim verbatim: "point [the governance
 * decision log] at Kafka or Postgres ({@code ai-audit-kafka},
 * {@code ai-audit-postgres}) and every admit and deny is persisted."
 *
 * <p>This is a <b>delivery</b> test, not a wiring assertion. It boots a real
 * Kafka broker and a real Postgres via Testcontainers, registers
 * {@link KafkaAuditSink} and {@link JdbcAuditSink} on the
 * {@link GovernanceDecisionLog}, drives one admit and one deny through the
 * sample's actual policy plane ({@link PoliciesConfig#msAgentOsPolicyLoader}
 * loading {@code atmosphere-policies.yaml}) via the exact entry point the
 * sample's {@code @Prompt} handler uses ({@link PolicyAdmissionGate#admit}),
 * then reads the records back out of both stores and asserts the persisted
 * decision + agent + endpoint content.</p>
 *
 * <p>The single deviation from the sample's literal wiring is a fixed
 * {@link Clock} on the business-hours window so the test never flakes on the
 * wall clock — same window / zone / days as {@link PoliciesConfig#businessHours()}.</p>
 *
 * <p>Requires Docker. When Docker is unavailable the test aborts (Assumptions)
 * rather than failing — but it is a RAN, asserting test under Docker, not an
 * eternally-skipped placeholder.</p>
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceAuditSinkDeliveryTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private static final String TOPIC = "governance.audit";
    private static final String AUDIT_TABLE = "governance_audit_log";

    // Two distinct requests so the admit / deny records are unambiguous when
    // both stores are scanned (the deny request also emits incidental admit
    // records for the policies that pass before the YAML deny rule fires).
    private static final String ADMIT_MSG = "How do I check the status of my order?";
    private static final String DENY_MSG =
            "Please drop table customers from the production database.";
    private static final String ADMIT_AGENT = "support-admit-agent";
    private static final String DENY_AGENT = "support-deny-agent";
    private static final String ENDPOINT = "/atmosphere/ms-governance";
    private static final String TENANT = "acme-corp";
    private static final String ROLE = "support-chat-user";

    // The MS-schema document name from atmosphere-policies.yaml (`name:`).
    private static final String YAML_POLICY_NAME = "ms-customer-service-demo";

    private KafkaContainer kafka;
    private PostgreSQLContainer<?> postgres;
    private PGSimpleDataSource dataSource;
    private KafkaAuditSink kafkaSink;
    private AtmosphereFramework framework;

    @BeforeAll
    void setUp() throws Exception {
        if (!DOCKER_AVAILABLE) {
            return;
        }

        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafka.start();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();

        createTopic(kafka.getBootstrapServers(), TOPIC);

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // Register the two reference sinks on the decision log. JdbcAuditSink
        // auto-creates the `governance_audit_log` table (JSONB context column
        // on Postgres). KafkaAuditSink owns the producer it builds; both are
        // closed by GovernanceDecisionLog.reset() in tearDown (Inv #1).
        var jdbcSink = new JdbcAuditSink(dataSource);
        kafkaSink = new KafkaAuditSink(kafka.getBootstrapServers(), TOPIC);
        var log = GovernanceDecisionLog.install(500);
        log.addSink(kafkaSink);
        log.addSink(jdbcSink);

        // Publish the sample's real policy plane onto a real (un-init'd, hence
        // lightweight) framework, exactly as PoliciesConfig does at boot — the
        // only swap is a fixed-clock business-hours window for determinism.
        framework = new AtmosphereFramework();
        publishSamplePolicies(framework);
    }

    @AfterAll
    void tearDown() {
        // reset() closes every registered sink: Kafka producer flush+close,
        // JDBC sink no-op (it never owned the DataSource). Then stop containers.
        GovernanceDecisionLog.reset();
        if (postgres != null) {
            postgres.stop();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void admitAndDenyPersistToKafkaAndPostgres() throws Exception {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker not available");

        // ---- Drive one admit + one deny through the sample's governance ----
        var admit = PolicyAdmissionGate.admit(framework, request(ADMIT_MSG, ADMIT_AGENT));
        assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, admit,
                "benign in-hours request with tenant+role must be admitted");

        var deny = PolicyAdmissionGate.admit(framework, request(DENY_MSG, DENY_AGENT));
        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, deny,
                "destructive-SQL request must be denied by the YAML rule");
        assertEquals(YAML_POLICY_NAME, denied.policyName(),
                "deny attributed to the MS-schema YAML policy");
        assertTrue(denied.reason().contains("Destructive SQL"),
                "deny reason is the rule's message: " + denied.reason());

        // JDBC writes happen synchronously on the admission thread; flush Kafka
        // so the async producer's records reach the broker before we consume.
        kafkaSink.flush(Duration.ofSeconds(5));

        // ---- Read the records back from Kafka ----
        var kafkaValues = consumeAll(kafka.getBootstrapServers(), TOPIC, Duration.ofSeconds(30));

        var kafkaDeny = kafkaValues.stream()
                .filter(v -> v.contains("\"decision\":\"deny\""))
                .filter(v -> v.contains(DENY_AGENT))
                .findFirst()
                .orElse(null);
        assertNotNull(kafkaDeny, "Kafka topic must carry the deny event; got: " + kafkaValues);
        assertTrue(kafkaDeny.contains("\"policy_name\":\"" + YAML_POLICY_NAME + "\""),
                "Kafka deny record carries the policy name: " + kafkaDeny);
        assertTrue(kafkaDeny.contains("Destructive SQL"),
                "Kafka deny record carries the rule reason: " + kafkaDeny);
        assertTrue(kafkaDeny.contains("\"endpoint\":\"" + ENDPOINT + "\""),
                "Kafka deny record carries the endpoint in the context snapshot: " + kafkaDeny);

        var kafkaAdmit = kafkaValues.stream()
                .filter(v -> v.contains("\"decision\":\"admit\""))
                .filter(v -> v.contains(ADMIT_AGENT))
                .findFirst()
                .orElse(null);
        assertNotNull(kafkaAdmit, "Kafka topic must carry the admit event; got: " + kafkaValues);
        assertTrue(kafkaAdmit.contains("\"endpoint\":\"" + ENDPOINT + "\""),
                "Kafka admit record carries the endpoint: " + kafkaAdmit);

        // ---- Read the rows back from Postgres ----
        assertPostgresHasDecision("deny", DENY_AGENT, YAML_POLICY_NAME, "Destructive SQL");
        assertPostgresHasDecision("admit", ADMIT_AGENT, null, null);
    }

    /**
     * Asserts at least one {@code governance_audit_log} row exists with the
     * given decision whose JSONB context snapshot identifies {@code agentId}
     * (and, on Postgres, carries the endpoint). When {@code expectedPolicy} /
     * {@code reasonNeedle} are non-null they are checked too.
     */
    private void assertPostgresHasDecision(String decision, String agentId,
                                           String expectedPolicy, String reasonNeedle)
            throws Exception {
        var sql = "SELECT policy_name, decision, reason, context_snapshot::text AS ctx "
                + "FROM " + AUDIT_TABLE
                + " WHERE decision = ? AND context_snapshot::text LIKE ?";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, decision);
            stmt.setString(2, "%" + agentId + "%");
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next(),
                        "Postgres must hold a '" + decision + "' row for agent " + agentId);
                var ctx = rs.getString("ctx");
                assertNotNull(ctx, "context_snapshot JSONB must be present");
                assertTrue(ctx.contains(agentId),
                        "row context identifies the agent: " + ctx);
                assertTrue(ctx.contains(ENDPOINT),
                        "row context carries the endpoint: " + ctx);
                if (expectedPolicy != null) {
                    assertEquals(expectedPolicy, rs.getString("policy_name"),
                            "deny row attributed to the YAML policy");
                }
                if (reasonNeedle != null) {
                    assertTrue(rs.getString("reason").contains(reasonNeedle),
                            "deny row carries the rule reason: " + rs.getString("reason"));
                }
            }
        }
    }

    private AiRequest request(String message, String agentId) {
        return new AiRequest(message, "", null,
                "user-" + agentId, null, agentId, null,
                Map.of("tenant-id", TENANT, "roles", ROLE, "endpoint", ENDPOINT),
                List.of());
    }

    private void publishSamplePolicies(AtmosphereFramework fw) throws Exception {
        var cfg = new PoliciesConfig();
        var killSwitch = cfg.killSwitch();
        var counted = cfg.countedKillSwitch(killSwitch);
        // Same window/zone/days as PoliciesConfig.businessHours(), but pinned to
        // a fixed in-window instant (Tue 2026-06-30 12:00 America/New_York) so
        // the admit case never flakes on when CI runs.
        var businessHours = new TimeWindowPolicy(
                "support-business-hours", "code:" + PoliciesConfig.class.getName(), "1",
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                ZoneId.of("America/New_York"),
                Clock.fixed(Instant.parse("2026-06-30T16:00:00Z"), ZoneOffset.UTC));
        cfg.msAgentOsPolicyLoader(fw, counted, cfg.rateLimit(), cfg.concurrencyLimit(),
                businessHours, cfg.requireTenantId(), cfg.requireSupportRole());
    }

    private static void createTopic(String bootstrapServers, String topic) throws Exception {
        var props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (var admin = Admin.create(props)) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    private static List<String> consumeAll(String bootstrapServers, String topic,
                                           Duration budget) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-verify-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());

        var values = new ArrayList<String>();
        var deadline = System.nanoTime() + budget.toNanos();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500));
                records.forEach(rec -> values.add(rec.value()));
                boolean haveDeny = values.stream().anyMatch(v -> v.contains("\"decision\":\"deny\""));
                boolean haveAdmit = values.stream().anyMatch(v -> v.contains("\"decision\":\"admit\""));
                if (haveDeny && haveAdmit) {
                    break;
                }
            }
        }
        return values;
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
