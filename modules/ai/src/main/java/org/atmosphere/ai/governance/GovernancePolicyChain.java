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

import java.util.List;

/**
 * Carries the effective {@link GovernancePolicy} chain through the tool-execution
 * injectables map so {@code ToolExecutionHelper} can run per-tool-call admission
 * on dispatch paths that have no {@link org.atmosphere.cpr.AtmosphereResource} —
 * the {@code AiPipeline} channel bridges, A2A, AG-UI, and coordinator-local
 * paths. On the {@code @AiEndpoint} path the resource is present and tool-call
 * admission reads the framework off it, so this chain is only consulted when no
 * resource is in scope (the two are mutually exclusive — no double admission).
 *
 * <p>It is a typed wrapper rather than a bare {@code List} so it can be a unique
 * key in the {@code Map<Class<?>, Object>} injectables map.</p>
 */
public record GovernancePolicyChain(List<GovernancePolicy> policies) {

    public GovernancePolicyChain {
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }
}
