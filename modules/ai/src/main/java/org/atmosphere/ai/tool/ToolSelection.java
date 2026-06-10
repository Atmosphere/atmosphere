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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.extensibility.DynamicToolSelector;
import org.atmosphere.ai.extensibility.ToolDescriptor;
import org.atmosphere.ai.extensibility.ToolIndex;

import java.util.List;

/**
 * Dynamic tool pre-filtering for large tool catalogs. When an endpoint registers
 * more tools than the model should see per turn, this caps the set handed to the
 * model to the {@code maxTools} most relevant for the user's message — pre-filtering
 * with the in-tree {@link ToolIndex} / {@link DynamicToolSelector} lexical scorer
 * (token-overlap; an embedding scorer is a pluggable future).
 *
 * <p>This is the single production consumer that moves
 * {@link DynamicToolSelector} from "primitive with no caller" to wired: both the
 * {@code AiPipeline} (channel-bridge) and {@code AiStreamingSession}
 * ({@code @AiEndpoint}) tool-assembly seams call {@link #select} so the cap is
 * applied identically across stream and non-stream modes (Mode Parity, Invariant #7).</p>
 */
public final class ToolSelection {

    private ToolSelection() {
    }

    /**
     * Select up to {@code maxTools} tools from {@code registry} most relevant to
     * {@code query}.
     *
     * @param registry the full tool catalog
     * @param query    the user's message (drives lexical relevance)
     * @param maxTools cap; {@code <= 0} or a catalog already at/under the cap
     *                 returns every tool unchanged (backward compatible)
     * @return the (possibly reduced) tool list; never empty when the registry
     *         is non-empty (an all-misses query falls back to a deterministic
     *         first-N rather than stripping the model of tools)
     */
    public static List<ToolDefinition> select(ToolRegistry registry, String query, int maxTools) {
        if (registry == null) {
            return List.of();
        }
        var all = List.copyOf(registry.allTools());
        if (maxTools <= 0 || all.size() <= maxTools) {
            return all;
        }
        var index = new ToolIndex();
        for (var tool : all) {
            index.register(new ToolDescriptor(tool.name(), tool.description(), List.of()));
        }
        var picked = new DynamicToolSelector(index, maxTools).select(query);
        if (picked.isEmpty()) {
            // No lexical overlap with the query — fall back to a deterministic
            // first-N (the blank-query path) so the model keeps a usable subset
            // instead of losing every tool.
            picked = index.search("", maxTools);
        }
        var ids = picked.stream().map(ToolDescriptor::id).toList();
        var selected = registry.getTools(ids);
        return selected.isEmpty() ? all : List.copyOf(selected);
    }
}
