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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.governance.DryRunPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.GuardrailAsPolicy;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads governance policies from {@code classpath:atmosphere-policies.yaml}
 * and publishes them to {@link GovernancePolicy#POLICIES_PROPERTY} so
 * {@code AiEndpointProcessor} picks them up for every {@code @AiEndpoint}
 * in this app — including {@link AiClassroom}.
 *
 * <p>The atmosphere-policies.yaml file is the operator-editable source of
 * truth: change the YAML, restart, and governance changes with zero code
 * edits. This is the central promise of the Phase A policy plane.</p>
 */
@Configuration
public class PoliciesConfig {

    private static final Logger logger = LoggerFactory.getLogger(PoliciesConfig.class);
    private static final String POLICY_FILE = "atmosphere-policies.yaml";

    @Bean
    public PolicyPlaneLoader atmospherePolicyPlaneLoader(AtmosphereFramework framework) throws IOException {
        var resource = new ClassPathResource(POLICY_FILE);
        if (!resource.exists()) {
            logger.info("No {} on the classpath — policy plane disabled for this sample",
                    POLICY_FILE);
            return new PolicyPlaneLoader(List.of());
        }
        List<GovernancePolicy> policies;
        try (var in = resource.getInputStream()) {
            policies = loadPolicies("classpath:" + POLICY_FILE, in);
        }
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, policies);
        logger.info("Published {} governance policies from {} to AiEndpointProcessor: {}",
                policies.size(), POLICY_FILE,
                policies.stream().map(GovernancePolicy::name).toList());
        return new PolicyPlaneLoader(policies);
    }

    /**
     * Parses the policy YAML into the policies this sample installs. A
     * response-length z-score policy is installed in dry-run: it flags an
     * outlier (audit entry, shadow-deny counter, log line) and never blocks.
     *
     * <p>Enforced, it cuts ordinary long answers off mid-stream. A streamed
     * reply is inspected every 200 characters and once complete, and the
     * guardrail counts each inspection as a sample, so its baseline is a mean
     * of partial lengths: after a few short answers, a normal 1,600-character
     * lesson crosses 3 sigma part-way through.</p>
     */
    static List<GovernancePolicy> loadPolicies(String source, InputStream in) throws IOException {
        return new YamlPolicyParser().parse(source, in).stream()
                .map(PoliciesConfig::flagOnlyIfLengthDrift)
                .toList();
    }

    private static GovernancePolicy flagOnlyIfLengthDrift(GovernancePolicy policy) {
        if (policy instanceof GuardrailAsPolicy adapter
                && adapter.guardrail() instanceof OutputLengthZScoreGuardrail) {
            return new DryRunPolicy(policy);
        }
        return policy;
    }

    /** Marker bean that also exposes the loaded policies for admin/diagnostics. */
    public record PolicyPlaneLoader(List<GovernancePolicy> policies) { }
}
