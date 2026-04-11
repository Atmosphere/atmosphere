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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read operations for AI runtime inspection — available runtimes,
 * active runtime, and capabilities.
 *
 * @since 4.0
 */
public final class AiRuntimeController {

    /**
     * List all available AI runtimes with their metadata.
     */
    public List<Map<String, Object>> listRuntimes() {
        var runtimes = AgentRuntimeResolver.resolveAll();
        var result = new ArrayList<Map<String, Object>>(runtimes.size());
        for (AgentRuntime runtime : runtimes) {
            result.add(describe(runtime));
        }
        return result;
    }

    /**
     * Get the currently active (highest-priority available) runtime.
     */
    public Map<String, Object> getActiveRuntime() {
        return describe(AgentRuntimeResolver.resolve());
    }

    /**
     * Shared runtime-description builder. Reports name, priority, availability,
     * capabilities, and the list of configured models. The {@code models} list
     * is whatever the runtime reports via {@link AgentRuntime#models()} —
     * runtimes with deterministic model hints return their configured model;
     * runtimes whose model selection is per-request (ADK, Koog) return an
     * empty list, which is still honest.
     */
    private static Map<String, Object> describe(AgentRuntime runtime) {
        var info = new LinkedHashMap<String, Object>();
        info.put("name", runtime.name());
        info.put("priority", runtime.priority());
        info.put("isAvailable", runtime.isAvailable());
        var capabilities = new ArrayList<String>();
        for (AiCapability cap : runtime.capabilities()) {
            capabilities.add(cap.name());
        }
        info.put("capabilities", capabilities);
        info.put("models", new ArrayList<>(runtime.models()));
        return info;
    }
}
