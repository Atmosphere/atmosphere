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
package org.atmosphere.ai.preset;

import java.util.EnumSet;
import java.util.Set;

/**
 * The granular harness features an {@code @Agent} / {@code @AiEndpoint} can
 * request from the deep-agent category of primitives. Declared on the
 * annotations' {@code harness()} attribute and resolved against the app-wide
 * config by {@link HarnessPreset#featuresFor}.
 */
public enum Harness {

    /**
     * The auto-attached long-term-memory interceptor, the compaction seam on
     * the resolved memory, and — on {@code @AiEndpoint} paths whose
     * annotation keeps {@code conversationMemory = false} — flipping
     * conversation memory on. {@code @Agent} and {@code @Coordinator}
     * resolve conversation memory unconditionally; the harness never turns
     * it off there.
     */
    MEMORY,

    /**
     * Prompt-cache default seeding — endpoints whose annotation keeps
     * {@code promptCache = NONE} are seeded with a conservative policy.
     */
    CACHE,

    /**
     * The fleet delegation primitive — the built-in {@code delegate_task}
     * tool and the governance wrap on the outbound dispatch edge.
     */
    DELEGATION,

    /**
     * The planning primitive — the agent maintains a plan it exposes and
     * updates: the built-in {@code write_todos} tool floor (or a native plan
     * surface when the resolved runtime advertises
     * {@code AiCapability.PLANNING} under the
     * {@code atmosphere.ai.planning} AUTO default), persisted per
     * conversation and streamed as {@code plan-update} events.
     */
    PLANNING,

    /**
     * The virtual-filesystem primitive — a bounded, conversation-scoped file
     * store under the agent workspace: the built-in {@code ls} /
     * {@code read_file} / {@code write_file} / {@code edit_file} /
     * {@code glob} / {@code grep} tool floor (or a native file surface when
     * the resolved runtime advertises {@code AiCapability.VIRTUAL_FILESYSTEM}
     * under the {@code atmosphere.ai.filesystem} AUTO default).
     */
    FILESYSTEM,

    /**
     * Sentinel that expands to {@link #MEMORY}, {@link #CACHE},
     * {@link #DELEGATION}, {@link #PLANNING} and {@link #FILESYSTEM} — the
     * full harness.
     */
    ALL;

    /**
     * Resolve a declared {@code harness()} attribute to the concrete feature
     * set: {@link #ALL} expands to every concrete feature, duplicates
     * collapse, and an empty (or {@code null}) declaration stays empty.
     *
     * @param declared the annotation's {@code harness()} value
     * @return the expanded feature set — never {@code null}, never contains
     *         {@link #ALL}
     */
    public static Set<Harness> expand(Harness[] declared) {
        var resolved = EnumSet.noneOf(Harness.class);
        if (declared == null) {
            return resolved;
        }
        for (var feature : declared) {
            if (feature == ALL) {
                resolved.add(MEMORY);
                resolved.add(CACHE);
                resolved.add(DELEGATION);
                resolved.add(PLANNING);
                resolved.add(FILESYSTEM);
            } else {
                resolved.add(feature);
            }
        }
        return resolved;
    }
}
