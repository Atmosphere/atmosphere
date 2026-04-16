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
package org.atmosphere.ai.extensibility;

import java.util.List;
import java.util.Objects;

/**
 * Selects a bounded subset of tools for a given user request. Backs the
 * {@code @AiEndpoint(maxToolsPerRequest = N)} hint so agents with many
 * registered tools do not inject every single descriptor into every LLM
 * call.
 */
public final class DynamicToolSelector {

    private final ToolIndex index;
    private final int defaultLimit;

    public DynamicToolSelector(ToolIndex index, int defaultLimit) {
        if (defaultLimit <= 0) {
            throw new IllegalArgumentException("defaultLimit must be > 0, got " + defaultLimit);
        }
        this.index = Objects.requireNonNull(index, "index");
        this.defaultLimit = defaultLimit;
    }

    /**
     * Return up to {@link #defaultLimit} tools relevant to the query. The
     * shape of the return list is stable for the same query and index
     * state, so CI diffs are deterministic.
     */
    public List<ToolDescriptor> select(String query) {
        return index.search(query, defaultLimit);
    }

    /** Select with an explicit per-call limit, overriding the default. */
    public List<ToolDescriptor> select(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return index.search(query, limit);
    }

    public int defaultLimit() {
        return defaultLimit;
    }
}
