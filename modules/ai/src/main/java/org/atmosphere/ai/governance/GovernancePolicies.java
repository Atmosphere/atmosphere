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
package org.atmosphere.ai.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the declarative {@link GovernancePolicy} chain installed for a
 * framework, merging {@link ServiceLoader} discoveries with the framework-scoped
 * {@link GovernancePolicy#POLICIES_PROPERTY} bag (populated by the
 * {@code AiEndpointProcessor}, Spring / Quarkus auto-config, or programmatic
 * publish). Deduplicates by {@link GovernancePolicy#name()} so repeat wiring
 * (Spring + ServiceLoader + YAML pre-loaded into the property) cannot
 * double-install the same policy.
 *
 * <p>This is the single source of truth so every agent-dispatch entry point —
 * the {@code @AiEndpoint} streaming path, the {@code @Agent}-derived A2A / AG-UI
 * / channel pipelines, and the {@code @Coordinator} pipeline — admits against
 * the same installed policies (Correctness Invariant #7, Mode Parity). Before
 * this helper existed the non-{@code @AiEndpoint} pipelines were constructed
 * with an empty policy list, so governance was silently absent on those
 * surfaces.</p>
 */
public final class GovernancePolicies {

    private static final Logger logger = LoggerFactory.getLogger(GovernancePolicies.class);

    private GovernancePolicies() {
    }

    /**
     * Merge ServiceLoader-discovered and framework-scoped governance policies,
     * deduplicated by name. Returns an empty list when {@code framework} is
     * {@code null} or no policies are installed.
     */
    public static List<GovernancePolicy> installed(AtmosphereFramework framework) {
        var merged = new LinkedHashMap<String, GovernancePolicy>();
        try {
            for (var p : ServiceLoader.load(GovernancePolicy.class)) {
                if (p != null) {
                    merged.putIfAbsent(p.name(), p);
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.warn("GovernancePolicy ServiceLoader lookup failed: {}", e.getMessage());
        }
        if (framework != null) {
            var cfg = framework.getAtmosphereConfig();
            if (cfg != null) {
                var bridged = cfg.properties().get(GovernancePolicy.POLICIES_PROPERTY);
                if (bridged instanceof List<?> list) {
                    for (var p : list) {
                        if (p instanceof GovernancePolicy policy) {
                            merged.putIfAbsent(policy.name(), policy);
                        }
                    }
                }
            }
        }
        return List.copyOf(merged.values());
    }
}
