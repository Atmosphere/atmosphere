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
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
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

    @Bean
    public PolicyPlaneLoader msAgentOsPolicyLoader(AtmosphereFramework framework) throws IOException {
        var resource = new ClassPathResource(POLICY_FILE);
        if (!resource.exists()) {
            logger.info("No {} on the classpath — policy plane disabled", POLICY_FILE);
            return new PolicyPlaneLoader(List.of());
        }
        List<GovernancePolicy> policies;
        try (var in = resource.getInputStream()) {
            policies = new YamlPolicyParser().parse("classpath:" + POLICY_FILE, in);
        }
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, policies);
        logger.info("Published {} MS-schema policies from {} to AiEndpointProcessor: {}",
                policies.size(), POLICY_FILE,
                policies.stream().map(GovernancePolicy::name).toList());
        return new PolicyPlaneLoader(policies);
    }

    public record PolicyPlaneLoader(List<GovernancePolicy> policies) { }
}
