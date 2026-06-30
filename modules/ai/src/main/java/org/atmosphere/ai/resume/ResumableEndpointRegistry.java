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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.tool.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Process-wide map from an {@code @AiEndpoint} path to the live runtime and tool
 * set needed to re-drive one of its runs. The reconnect-triggered resume has this
 * context locally (it <em>is</em> the endpoint's handler), but an admin-triggered
 * re-drive starts from only a run id; it reads the run seed's
 * {@link EffectRecord.RunSeed#endpointPath() endpointPath} and looks the live
 * context up here so it can reconstruct and dispatch the run. Each
 * {@code AiEndpointHandler} registers itself on construction.
 *
 * @since 4.0
 */
public final class ResumableEndpointRegistry {

    /** The live re-drive context for one endpoint path. */
    public record EndpointContext(AgentRuntime runtime, Supplier<List<ToolDefinition>> tools) {
    }

    private static final ConcurrentHashMap<String, EndpointContext> ENDPOINTS = new ConcurrentHashMap<>();

    private ResumableEndpointRegistry() {
        // static registry
    }

    /** Register an endpoint's live re-drive context, keyed by its path. */
    public static void register(String endpointPath, AgentRuntime runtime,
                                Supplier<List<ToolDefinition>> tools) {
        if (endpointPath != null && runtime != null && tools != null) {
            ENDPOINTS.put(endpointPath, new EndpointContext(runtime, tools));
        }
    }

    /** The re-drive context for {@code endpointPath}, if an endpoint is registered there. */
    public static Optional<EndpointContext> lookup(String endpointPath) {
        return endpointPath == null ? Optional.empty() : Optional.ofNullable(ENDPOINTS.get(endpointPath));
    }

    /** Drop a registration (endpoint teardown). */
    public static void remove(String endpointPath) {
        if (endpointPath != null) {
            ENDPOINTS.remove(endpointPath);
        }
    }

    /** Clear all registrations. Primarily for test isolation. */
    public static void clear() {
        ENDPOINTS.clear();
    }
}
