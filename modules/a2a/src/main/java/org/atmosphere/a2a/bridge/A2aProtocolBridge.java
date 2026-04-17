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
package org.atmosphere.a2a.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.cpr.AtmosphereFramework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ProtocolBridge} that enumerates agents reachable via A2A JSON-RPC.
 * Agents are exposed at {@code /atmosphere/agent/{name}/a2a} or the legacy
 * {@code /atmosphere/a2a/{name}} — both path shapes are surfaced.
 */
public final class A2aProtocolBridge implements ProtocolBridge {

    public static final String NAME = "a2a";

    private static final String AGENT_A2A_SUFFIX = "/a2a";
    private static final String AGENT_PATH_PREFIX = "/atmosphere/agent/";
    private static final String LEGACY_A2A_PREFIX = "/atmosphere/a2a/";

    private final AtmosphereFramework framework;

    public A2aProtocolBridge(AtmosphereFramework framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Kind kind() {
        return Kind.WIRE;
    }

    @Override
    public boolean isActive() {
        return framework != null && framework.initialized() && !agentPaths().isEmpty();
    }

    @Override
    public String describe() {
        return "A2A JSON-RPC — agents exposed at /atmosphere/agent/{name}/a2a "
                + "or /atmosphere/a2a/{name}";
    }

    @Override
    public List<String> agentPaths() {
        if (framework == null || !framework.initialized()) {
            return List.of();
        }
        var paths = new ArrayList<String>();
        for (var key : framework.getAtmosphereHandlers().keySet()) {
            if ((key.startsWith(AGENT_PATH_PREFIX) && key.endsWith(AGENT_A2A_SUFFIX))
                    || key.startsWith(LEGACY_A2A_PREFIX)) {
                paths.add(key);
            }
        }
        return List.copyOf(paths);
    }

    @Override
    public int order() {
        return 30;
    }
}
