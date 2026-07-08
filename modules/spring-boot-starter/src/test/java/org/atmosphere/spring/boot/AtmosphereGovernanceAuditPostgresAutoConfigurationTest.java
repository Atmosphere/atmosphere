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

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery test for the {@code ai-audit-postgres} half of the blog's
 * "the decision log can be pointed at Kafka or Postgres; every admit and deny
 * persisted" claim.
 *
 * <p>Drives a real admit AND a real deny through the installed
 * {@link GovernanceDecisionLog} while the auto-configuration has pointed it at
 * an in-process H2 (Postgres-shaped) store, then asserts BOTH rows landed in
 * the table — an observable side effect, not merely that the sink type exists.
 * Also pins the default-OFF gate: with no URL, no sink is registered.</p>
 */
class AtmosphereGovernanceAuditPostgresAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereGovernanceAuditPostgresAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetLog() {
        // Process-wide singleton — reset around each run so one test's sink
        // registration cannot leak into another's assertion.
        GovernanceDecisionLog.reset();
    }

    @Test
    void persistsAdmitAndDenyThroughTheDecisionLog() throws Exception {
        var url = "jdbc:h2:mem:gov-audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.governance.audit.postgres.url=" + url,
                        // synchronous so the row is present the moment record() returns
                        "atmosphere.ai.governance.audit.async=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    var log = GovernanceDecisionLog.installed();
                    assertThat(log.capacity())
                            .as("audit auto-config must install a non-NOOP decision log")
                            .isGreaterThan(0);
                    assertThat(log.sinks())
                            .as("the JDBC audit sink must be registered")
                            .anyMatch(s -> s.name().equals("jdbc:governance_audit_log"));

                    log.record(entry("admit", ""));
                    log.record(entry("deny", "matched prompt-injection probe"));

                    var decisions = readDecisions(url);
                    assertThat(decisions)
                            .as("both the admit and the deny must be persisted")
                            .containsExactlyInAnyOrder("admit", "deny");
                });
    }

    @Test
    void defaultsToAsyncBoundedSinkForBackpressure() {
        var url = "jdbc:h2:mem:gov-audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        contextRunner
                .withPropertyValues("atmosphere.ai.governance.audit.postgres.url=" + url)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(GovernanceDecisionLog.installed().sinks())
                            .as("default (async=true) must wrap the sink in the bounded "
                                    + "AsyncAuditSink so admission never blocks on JDBC")
                            .anyMatch(s -> s.name().startsWith("async:jdbc:"));
                });
    }

    @Test
    void featureIsOffWithoutAUrl() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("no URL => auto-config must not activate (default OFF)")
                    .doesNotHaveBean(AtmosphereGovernanceAuditPostgresAutoConfiguration.class);
            assertThat(GovernanceDecisionLog.installed().capacity())
                    .as("no sink, no forced decision-log install")
                    .isZero();
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

    private static List<String> readDecisions(String url) throws Exception {
        var decisions = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(url);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT decision FROM governance_audit_log ORDER BY id")) {
            while (rs.next()) {
                decisions.add(rs.getString("decision"));
            }
        }
        return decisions;
    }
}
