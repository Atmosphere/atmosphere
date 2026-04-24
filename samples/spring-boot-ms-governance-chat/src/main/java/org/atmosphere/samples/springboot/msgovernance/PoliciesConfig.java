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

import org.atmosphere.ai.governance.AuthorizationPolicy;
import org.atmosphere.ai.governance.ConcurrencyLimitPolicy;
import org.atmosphere.ai.governance.CountingPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.MetadataPresencePolicy;
import org.atmosphere.ai.governance.PhaseScopedPolicy;
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.ai.governance.TimeWindowPolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Loads Microsoft Agent Governance Toolkit YAML verbatim from the classpath
 * and publishes the resulting policies onto
 * {@link GovernancePolicy#POLICIES_PROPERTY} so {@code AiEndpointProcessor}
 * and the {@code /api/admin/governance/check} endpoint both enforce them.
 * The file is the MS schema (rules-over-context) — no translation layer,
 * no compatibility wrapper.
 */
@Configuration
public class PoliciesConfig {

    private static final Logger logger = LoggerFactory.getLogger(PoliciesConfig.class);
    private static final String POLICY_FILE = "atmosphere-policies.yaml";

    /**
     * Operator break-glass switch. Published as a bean so an admin endpoint
     * (or an incident-response script) can inject it and call
     * {@link KillSwitchPolicy#arm(String, String)} / {@link KillSwitchPolicy#disarm()}
     * without restarting the app.
     */
    @Bean
    public KillSwitchPolicy killSwitch() {
        return new KillSwitchPolicy();
    }

    /**
     * Per-user sliding-window limiter: 30 requests / 60s. Conservative
     * default for the demo — production deployments would key on tenant
     * or API key via {@link RateLimitPolicy#withSubjectOf}.
     */
    @Bean
    public RateLimitPolicy rateLimit() {
        return new RateLimitPolicy("per-user-rate-limit", 30, Duration.ofSeconds(60));
    }

    /**
     * Cap simultaneous in-flight chats at 3 per user — complements the
     * time-windowed rate limit above. A pathological client that
     * opens dozens of streams at once is bounded even if each stream
     * is inside the 30/60s envelope.
     */
    @Bean
    public ConcurrencyLimitPolicy concurrencyLimit() {
        return new ConcurrencyLimitPolicy("per-user-concurrency-limit", 3);
    }

    /**
     * Support-chat business hours: 08:00–20:00 America/New_York, Mon–Sat.
     * Off-hours turns deny with a clear reason; operators who want 24/7
     * swap the zone / days / times here. Demonstrates the window-based
     * denial shape without coupling to any specific wall-clock assumption.
     */
    @Bean
    public TimeWindowPolicy businessHours() {
        return new TimeWindowPolicy(
                "support-business-hours", "code:" + getClass().getName(), "1",
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                ZoneId.of("America/New_York"),
                Clock.systemUTC());
    }

    /**
     * Require every request to carry a tenant identifier on
     * {@link org.atmosphere.ai.AiRequest#metadata()} so per-tenant cost,
     * audit, and rate attribution all land correctly. Anonymous traffic
     * is denied at admission — the explicit shape a multi-tenant SaaS
     * support bot needs.
     */
    @Bean
    public MetadataPresencePolicy requireTenantId() {
        return new MetadataPresencePolicy(
                "require-tenant-id",
                "code:" + getClass().getName(), "1",
                List.of("tenant-id"));
    }

    /**
     * Role-based gate on the support chat — any caller must hold the
     * {@code support-chat-user} role. Resolver reads the role list from
     * {@link org.atmosphere.ai.AiRequest#metadata()} {@code roles} key,
     * matching what an upstream auth layer (Spring Security / JWT claim
     * mapper) stamps before the request reaches the pipeline. Production
     * deployments swap the resolver for a direct claim accessor.
     */
    @Bean
    public AuthorizationPolicy requireSupportRole() {
        return new AuthorizationPolicy(
                "require-support-role",
                "code:" + getClass().getName(), "1",
                List.of("support-chat-user"),
                AuthorizationPolicy::defaultRoleResolver);
    }

    /**
     * Diagnostic counter — admin dashboards read the admit / deny
     * tallies without a Micrometer backend. Wrapping the deny-list
     * (declared in {@code atmosphere-policies.yaml}) means operators
     * see how often the SQL / PII / discount rules actually fire.
     */
    @Bean
    public CountingPolicy countedKillSwitch(KillSwitchPolicy killSwitch) {
        // Counter around the kill switch specifically — ops want a
        // stand-alone "how many denies has THIS primitive issued" gauge.
        return CountingPolicy.of(killSwitch);
    }

    @Bean
    public PolicyPlaneLoader msAgentOsPolicyLoader(AtmosphereFramework framework,
                                                    CountingPolicy countedKillSwitch,
                                                    RateLimitPolicy rateLimit,
                                                    ConcurrencyLimitPolicy concurrencyLimit,
                                                    TimeWindowPolicy businessHours,
                                                    MetadataPresencePolicy requireTenantId,
                                                    AuthorizationPolicy requireSupportRole)
            throws IOException {
        var yamlPolicies = loadYaml();

        // Compose the full policy list. Order matters:
        //   (1) Operator break-glass (counted so ops can see denies).
        //   (2) Quick-reject gates: auth, tenant presence, rate, concurrency.
        //   (3) Time-window — allow specialized post-response audit to
        //       still run even off-hours, so the policy is scoped to
        //       PRE_ADMISSION via PhaseScopedPolicy.preAdmissionOnly.
        //   (4) YAML policies (MS schema rules-over-context) run last so
        //       they see the already-admitted shape.
        //
        // Every policy is wrapped in TimedPolicy for per-policy latency
        // metrics under `atmosphere.governance.policy.evaluation`.
        var composed = new ArrayList<GovernancePolicy>();
        composed.add(TimedPolicy.of(countedKillSwitch));
        composed.add(TimedPolicy.of(requireSupportRole));
        composed.add(TimedPolicy.of(requireTenantId));
        composed.add(TimedPolicy.of(rateLimit));
        composed.add(TimedPolicy.of(concurrencyLimit));
        composed.add(TimedPolicy.of(PhaseScopedPolicy.preAdmissionOnly(businessHours)));
        yamlPolicies.forEach(p -> composed.add(TimedPolicy.of(p)));

        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, composed);
        logger.info("Published {} policies ({} operator + {} YAML) to AiEndpointProcessor",
                composed.size(), composed.size() - yamlPolicies.size(), yamlPolicies.size());
        return new PolicyPlaneLoader(composed);
    }


    private List<GovernancePolicy> loadYaml() throws IOException {
        var resource = new ClassPathResource(POLICY_FILE);
        if (!resource.exists()) {
            logger.info("No {} on the classpath — YAML policy layer skipped", POLICY_FILE);
            return List.of();
        }
        try (var in = resource.getInputStream()) {
            return new YamlPolicyParser().parse("classpath:" + POLICY_FILE, in);
        }
    }

    public record PolicyPlaneLoader(List<GovernancePolicy> policies) { }
}
