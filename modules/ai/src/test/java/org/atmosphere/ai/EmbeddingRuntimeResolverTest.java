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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbeddingRuntimeResolverTest {

    @BeforeEach
    void resetCache() {
        EmbeddingRuntimeResolver.resetCache();
    }

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
        EmbeddingRuntimeResolver.resetCache();
    }

    /**
     * An application whose settings are its own (a Spring Boot starter's
     * {@code atmosphere.ai.*} settings, a plain servlet's init-params) and the
     * only one running: the statics resolve its embedding runtimes, as
     * {@code AiConfig.get()} returns its settings. Resolving the process
     * default's instead found no settings there and reported no runtime, so
     * the semantic cache and the embedding guardrails took their no-runtime path.
     */
    @Test
    void theStaticsResolveTheRunningApplicationsEmbeddingRuntimes() {
        AiConfig.resetForTesting();
        var environment = AiEnvironment.create(
                Map.of(AiConfig.EMBEDDING_MODEL_PROPERTY, "text-embedding-3-small")::get);
        environment.configure("remote", "gpt-4o-mini", "k", "http://127.0.0.1:1/v1");
        assertNull(AiEnvironment.processDefault().settings(), "the process default has no settings");
        assertTrue(environment.embeddings().resolve().isPresent(), "the application has an embedding runtime");

        var resolved = EmbeddingRuntimeResolver.resolve();

        assertTrue(resolved.isPresent(), "the statics resolve the application's embedding runtime");
        assertEquals(environment.embeddings().resolve().get().name(), resolved.get().name());
        assertEquals(environment.embeddings().resolveAll(), EmbeddingRuntimeResolver.resolveAll());
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
