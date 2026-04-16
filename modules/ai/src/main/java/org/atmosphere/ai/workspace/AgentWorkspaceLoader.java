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
package org.atmosphere.ai.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers {@link AgentWorkspace} adapters via {@link ServiceLoader} and
 * picks the first one in priority order whose
 * {@link AgentWorkspace#supports(Path)} returns {@code true}.
 *
 * <p>Built-in adapters ship via {@code META-INF/services/} and include
 * {@link OpenClawWorkspaceAdapter} (priority 10) and
 * {@link AtmosphereNativeWorkspaceAdapter} (priority {@link Integer#MAX_VALUE}).
 * Third-party adapters register themselves through the same mechanism.</p>
 *
 * <p>Instances are safe to construct once at application startup; the
 * adapter list is captured eagerly and reused for subsequent loads.</p>
 */
public final class AgentWorkspaceLoader {

    private final List<AgentWorkspace> adapters;

    /**
     * Construct a loader using service-loader discovery from the current
     * thread's context class loader.
     */
    public AgentWorkspaceLoader() {
        this(discover());
    }

    /**
     * Construct a loader with an explicit adapter list. Useful for tests and
     * embedded contexts where service-loader discovery is not appropriate.
     */
    public AgentWorkspaceLoader(List<AgentWorkspace> adapters) {
        var sorted = new ArrayList<>(adapters);
        sorted.sort(Comparator.comparingInt(AgentWorkspace::priority));
        this.adapters = List.copyOf(sorted);
    }

    /**
     * Return the adapters in priority order. Primarily exposed for admin
     * introspection.
     */
    public List<AgentWorkspace> adapters() {
        return adapters;
    }

    /**
     * Load the agent definition from the given workspace root. Throws
     * {@link IllegalStateException} if no registered adapter supports this
     * workspace (this should not happen when
     * {@link AtmosphereNativeWorkspaceAdapter} is registered — it accepts
     * any directory).
     */
    public AgentDefinition load(Path workspaceRoot) {
        for (var adapter : adapters) {
            if (adapter.supports(workspaceRoot)) {
                return adapter.load(workspaceRoot);
            }
        }
        throw new IllegalStateException(
                "No registered AgentWorkspace adapter supports " + workspaceRoot
                        + " (checked " + adapters.size() + " adapters)");
    }

    private static List<AgentWorkspace> discover() {
        var list = new ArrayList<AgentWorkspace>();
        for (var adapter : ServiceLoader.load(AgentWorkspace.class)) {
            list.add(adapter);
        }
        // Make sure the built-ins are always present even when no
        // META-INF/services registration is active (e.g. certain shaded
        // deployments) — the loader is useless without them.
        if (list.stream().noneMatch(a -> OpenClawWorkspaceAdapter.NAME.equals(a.name()))) {
            list.add(new OpenClawWorkspaceAdapter());
        }
        if (list.stream().noneMatch(a -> AtmosphereNativeWorkspaceAdapter.NAME.equals(a.name()))) {
            list.add(new AtmosphereNativeWorkspaceAdapter());
        }
        return list;
    }
}
