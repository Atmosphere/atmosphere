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
package org.atmosphere.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link StreamingSession} decorator that merges a fixed injectables map into
 * the tool-execution scope. The resource-free {@code AiPipeline} dispatch
 * paths (channel bridges, A2A, AG-UI, coordinator-local) never pass through
 * {@code AiEndpointHandler}, which is where the web path publishes the
 * endpoint's injectables (AgentFleet, AgentState, AgentPlanStore,
 * AgentFileSystemProvider, ...) onto the session — so without this decorator
 * the same registered tools would resolve their framework parameters on the
 * web path and fail on every other invocation mode (Correctness Invariant #7,
 * Mode Parity).
 *
 * <p>The merge is computed lazily on each {@link #injectables()} call, and
 * the delegate's own entries win on key conflict — a richer, dispatch-time
 * scope (the live {@code StreamingSession}, a per-session sandbox) must never
 * be shadowed by registration-time defaults.</p>
 */
final class ToolInjectablesSession extends DelegatingStreamingSession {

    private final Map<Class<?>, Object> extras;

    ToolInjectablesSession(StreamingSession delegate, Map<Class<?>, Object> extras) {
        super(delegate);
        this.extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    @Override
    public Map<Class<?>, Object> injectables() {
        var base = delegate.injectables();
        if (extras.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<Class<?>, Object>(extras);
        merged.putAll(base);
        return Map.copyOf(merged);
    }
}
