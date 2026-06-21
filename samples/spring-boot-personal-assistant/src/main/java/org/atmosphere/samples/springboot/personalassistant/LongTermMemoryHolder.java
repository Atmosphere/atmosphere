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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;

/**
 * Static handoff between the Spring-managed {@link LongTermMemoryConfig} bean
 * and the reflectively-instantiated {@link PersonalAssistantMemoryInterceptor}.
 * The {@code @AiEndpoint(interceptors=...)} scanner creates interceptors via
 * their no-arg constructor (see {@code AiEndpointProcessor.instantiateInterceptors}),
 * so this holder is the established way — matching
 * {@link McpToolSourceHolder} for the MCP tool source — to bridge a
 * framework-instantiated interceptor to its Spring-built dependencies.
 *
 * <p>The {@link LongTermMemory} backend is also exposed so beans (and tests)
 * can read/assert the stored facts directly.</p>
 */
final class LongTermMemoryHolder {

    private static volatile LongTermMemoryInterceptor interceptor;
    private static volatile LongTermMemory memory;

    private LongTermMemoryHolder() {
    }

    static void set(LongTermMemoryInterceptor interceptor, LongTermMemory memory) {
        LongTermMemoryHolder.interceptor = interceptor;
        LongTermMemoryHolder.memory = memory;
    }

    static LongTermMemoryInterceptor interceptor() {
        return interceptor;
    }

    static LongTermMemory memory() {
        return memory;
    }

    static void clear() {
        interceptor = null;
        memory = null;
    }
}
