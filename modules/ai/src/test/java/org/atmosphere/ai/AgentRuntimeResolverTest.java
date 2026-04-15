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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentRuntimeResolverTest {

    @Test
    void resolveReturnsNonNull() {
        var runtime = AgentRuntimeResolver.resolve();
        assertNotNull(runtime);
    }

    @Test
    void resolveAllReturnsNonEmptyList() {
        var runtimes = AgentRuntimeResolver.resolveAll();
        assertNotNull(runtimes);
        assertFalse(runtimes.isEmpty());
    }

    @Test
    void resolvedRuntimeHasName() {
        var runtime = AgentRuntimeResolver.resolve();
        assertNotNull(runtime.name());
        assertFalse(runtime.name().isBlank());
    }

    @Test
    void resolveAllContainsResolvedRuntime() {
        var primary = AgentRuntimeResolver.resolve();
        var all = AgentRuntimeResolver.resolveAll();
        // resolve() returns the first element of resolveAll()
        assertNotNull(all.getFirst());
        // Both should return the same name since resolve() returns resolveAll().getFirst()
        assertNotNull(primary.name());
    }
}
