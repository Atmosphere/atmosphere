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
package org.atmosphere.ai.bridge;

import java.util.List;

/**
 * A named way of reaching the {@code @Agent}s running in this Atmosphere
 * process. Every enabled bridge makes every registered agent addressable
 * through its particular protocol or transport — the Broadcaster pattern of
 * Atmosphere 1.0, applied to agent dispatch.
 *
 * <h2>In-JVM and wire bridges are symmetric</h2>
 *
 * Atmosphere deliberately treats in-JVM dispatch as a first-class
 * {@code ProtocolBridge} implementation ({@code InMemoryProtocolBridge}) on
 * equal footing with MCP, A2A, AG-UI, and gRPC. Local and remote agent calls
 * go through the same abstraction, see the same interceptors, and emit the
 * same observability events. An agent can move from in-JVM to remote without
 * changing its own code — a different {@link #kind()} of bridge simply
 * picks up the dispatch.
 *
 * <h2>Discovery</h2>
 *
 * Bridges are discovered via {@link java.util.ServiceLoader}. The
 * {@link ProtocolBridgeRegistry} collects them at startup and surfaces them
 * to the admin control plane so every deployment can answer "what protocols
 * is this agent reachable by right now?".
 *
 * <h2>Runtime truth</h2>
 *
 * {@link #isActive()} reports whether the bridge is actually serving
 * requests right now. Capability advertisement must reflect runtime state,
 * not configuration intent — when the backing server fails to bind, the
 * bridge returns {@code false}. This upholds Correctness Invariant #5
 * (Runtime Truth) for protocol discovery.
 */
public interface ProtocolBridge {

    /** What kind of bridge this is — determines the reachability story. */
    enum Kind {
        /** Dispatch happens within the same JVM, with no wire hop. */
        IN_JVM,
        /** Dispatch happens over a network transport. */
        WIRE
    }

    /**
     * Stable short identifier of this bridge
     * ({@code "in-memory"}, {@code "mcp"}, {@code "a2a"}, {@code "agui"},
     * {@code "grpc"}, or a third-party id).
     */
    String name();

    /** Whether this bridge is in-JVM or over a wire transport. */
    Kind kind();

    /**
     * Whether the bridge is currently reachable. For wire bridges this
     * reflects whether the backing server bound its listener successfully.
     * For in-JVM this is typically always {@code true}.
     */
    boolean isActive();

    /**
     * Human-readable descriptor of how this bridge reaches agents
     * (e.g. {@code "mcp: http://localhost:8080/atmosphere/agent/pierre/mcp"}
     * or {@code "in-JVM fleet dispatch"}). Surfaced on the admin control
     * plane for inspection.
     */
    String describe();

    /**
     * Agent paths or identifiers this bridge currently exposes. Empty when
     * no agents are registered or the bridge is inactive.
     */
    List<String> agentPaths();

    /**
     * Lower values sort first in the registry. Defaults to {@code 100}.
     * {@code InMemoryProtocolBridge} runs first so admin listings show
     * the in-JVM surface ahead of wire bridges.
     */
    default int order() {
        return 100;
    }
}
