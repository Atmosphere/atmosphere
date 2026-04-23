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

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.RateLimitPolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
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

    @Bean
    public PolicyPlaneLoader msAgentOsPolicyLoader(AtmosphereFramework framework,
                                                    KillSwitchPolicy killSwitch,
                                                    RateLimitPolicy rateLimit) throws IOException {
        var yamlPolicies = loadYaml();

        // Compose the full policy list: operator primitives first (cheap,
        // high-priority), then the YAML policies. Every policy is wrapped
        // in TimedPolicy so per-policy evaluation latency lands in
        // GovernanceMetrics automatically — operators see the cost of each
        // tier on `atmosphere.governance.policy.evaluation` without any
        // per-policy instrumentation.
        var composed = new ArrayList<GovernancePolicy>();
        composed.add(TimedPolicy.of(killSwitch));
        composed.add(TimedPolicy.of(rateLimit));
        yamlPolicies.forEach(p -> composed.add(TimedPolicy.of(p)));

        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, composed);
        logger.info("Published {} policies ({} operator + {} YAML) to AiEndpointProcessor",
                composed.size(), 2, yamlPolicies.size());
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
