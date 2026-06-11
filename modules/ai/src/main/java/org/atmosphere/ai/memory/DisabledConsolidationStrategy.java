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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntime;

import java.util.List;

/**
 * No-op {@link MemoryConsolidationStrategy}: never triggers and returns facts
 * untouched. The default so long-term memory behaves exactly as before unless
 * an application opts into consolidation. Identity-compared by the interceptor
 * so the common (disabled) path adds zero work.
 */
final class DisabledConsolidationStrategy implements MemoryConsolidationStrategy {

    static final DisabledConsolidationStrategy INSTANCE = new DisabledConsolidationStrategy();

    private DisabledConsolidationStrategy() {
        // singleton
    }

    @Override
    public boolean shouldConsolidate(String userId, int currentFactCount) {
        return false;
    }

    @Override
    public List<String> consolidate(List<String> facts, AgentRuntime runtime) {
        return facts;
    }
}
