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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EmbeddingRuntimeResolverTest {

    @BeforeEach
    void resetCache() {
        EmbeddingRuntimeResolver.resetCache();
    }

    @Test
    void resolveDoesNotThrow() {
        assertDoesNotThrow(EmbeddingRuntimeResolver::resolve);
    }

    @Test
    void resolveReturnsOptional() {
        var result = EmbeddingRuntimeResolver.resolve();
        assertNotNull(result);
    }

    @Test
    void resolveAllDoesNotThrow() {
        assertDoesNotThrow(EmbeddingRuntimeResolver::resolveAll);
    }

    @Test
    void resolveAllReturnsNonNullList() {
        List<EmbeddingRuntime> all = EmbeddingRuntimeResolver.resolveAll();
        assertNotNull(all);
    }

    @Test
    void resolveAllIsIdempotent() {
        var first = EmbeddingRuntimeResolver.resolveAll();
        var second = EmbeddingRuntimeResolver.resolveAll();
        assertNotNull(first);
        assertNotNull(second);
    }

    @Test
    void resetCacheAllowsRescan() {
        EmbeddingRuntimeResolver.resolveAll();
        EmbeddingRuntimeResolver.resetCache();
        var result = EmbeddingRuntimeResolver.resolveAll();
        assertNotNull(result);
    }
}
