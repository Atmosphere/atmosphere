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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.cpr.AtmosphereFramework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only admin introspection for the Atmosphere governance policy plane.
 * Enumerates policies currently installed on the framework via
 * {@link GovernancePolicy#POLICIES_PROPERTY} and reports their identity so
 * operators can answer "which policies are active on this deployment?" from
 * the admin console without reaching into framework internals.
 *
 * <p>Reports runtime-confirmed state only (Correctness Invariant #5, Runtime
 * Truth): the list reflects what {@code AiEndpointProcessor} will actually
 * apply on a turn, not what the YAML file or Spring beans might intend.</p>
 */
public final class GovernanceController {

    private final AtmosphereFramework framework;

    public GovernanceController(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * Enumerate active governance policies — one entry per policy, carrying
     * the stable identity ({@code name} / {@code source} / {@code version})
     * and a {@code className} hint so operators can tell e.g. a custom-built
     * policy from an adapter-wrapped {@code AiGuardrail}.
     */
    public List<Map<String, Object>> listPolicies() {
        var policies = readPolicies();
        var result = new ArrayList<Map<String, Object>>(policies.size());
        for (var policy : policies) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", policy.name());
            entry.put("source", policy.source());
            entry.put("version", policy.version());
            entry.put("className", policy.getClass().getName());
            result.add(entry);
        }
        return result;
    }

    /** Summary: policy count and distinct source URIs. */
    public Map<String, Object> summary() {
        var policies = readPolicies();
        var sources = new java.util.LinkedHashSet<String>();
        for (var policy : policies) {
            sources.add(policy.source());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("policyCount", policies.size());
        result.put("sources", List.copyOf(sources));
        return result;
    }

    private List<GovernancePolicy> readPolicies() {
        if (framework == null) {
            return List.of();
        }
        var config = framework.getAtmosphereConfig();
        if (config == null) {
            return List.of();
        }
        var raw = config.properties().get(GovernancePolicy.POLICIES_PROPERTY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        var result = new ArrayList<GovernancePolicy>(list.size());
        for (var entry : list) {
            if (entry instanceof GovernancePolicy policy) {
                result.add(policy);
            }
        }
        return List.copyOf(result);
    }
}
