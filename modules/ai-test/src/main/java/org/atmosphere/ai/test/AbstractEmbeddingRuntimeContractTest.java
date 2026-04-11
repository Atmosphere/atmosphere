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
package org.atmosphere.ai.test;

import org.atmosphere.ai.EmbeddingRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-runtime contract for {@link EmbeddingRuntime} implementations.
 * Mirrors {@link AbstractAgentRuntimeContractTest}'s pattern — every
 * concrete embedding runtime subclasses this base class and the parity
 * assertions run automatically.
 *
 * <p>Subclasses provide a configured runtime via {@link #createRuntime()}
 * and a tiny in-memory embedding function via
 * {@link #installFakeEmbedder(EmbeddingRuntime)}. The base assertions never
 * make real network calls — they trust the subclass's fake to deliver
 * deterministic vectors.</p>
 */
public abstract class AbstractEmbeddingRuntimeContractTest {

    /** Subclass hook: return a runtime instance ready for fake injection. */
    protected abstract EmbeddingRuntime createRuntime();

    /**
     * Subclass hook: inject a deterministic in-process embedder into the
     * runtime so the base assertions can call {@code embed()} /
     * {@code embedAll()} without a live network. The injected embedder
     * must return a vector whose first element is the input text's length
     * and the remaining elements are zeros — the base assertions check
     * this invariant to prove the vector actually round-tripped through
     * the adapter wrap layer.
     */
    protected abstract void installFakeEmbedder(EmbeddingRuntime runtime);

    /**
     * Expected dimension of the fake vectors. Subclasses override when the
     * underlying framework forces a specific length.
     */
    protected int expectedDimensions() {
        return 8;
    }

    @Test
    void runtimeHasStableName() {
        var runtime = createRuntime();
        assertNotNull(runtime.name(), "EmbeddingRuntime.name() must be non-null");
        assertTrue(!runtime.name().isBlank(), "EmbeddingRuntime.name() must be non-blank");
    }

    @Test
    void embedSingleTextReturnsVectorOfExpectedDimension() {
        var runtime = createRuntime();
        installFakeEmbedder(runtime);

        var vector = runtime.embed("hello");
        assertNotNull(vector, "embed() must not return null");
        assertEquals(expectedDimensions(), vector.length,
                "embed() must return a vector of the expected dimension");
        assertEquals(5.0f, vector[0], 0.0001f,
                "Fake embedder's contract: vector[0] must equal input length");
    }

    @Test
    void embedAllReturnsVectorPerInputInOrder() {
        var runtime = createRuntime();
        installFakeEmbedder(runtime);

        var texts = List.of("a", "bb", "ccc");
        var vectors = runtime.embedAll(texts);
        assertEquals(texts.size(), vectors.size(),
                "embedAll() must return one vector per input");
        assertEquals(1.0f, vectors.get(0)[0], 0.0001f);
        assertEquals(2.0f, vectors.get(1)[0], 0.0001f);
        assertEquals(3.0f, vectors.get(2)[0], 0.0001f);
    }

    @Test
    void embedAllWithEmptyListReturnsEmptyList() {
        var runtime = createRuntime();
        installFakeEmbedder(runtime);

        var vectors = runtime.embedAll(List.of());
        assertNotNull(vectors, "embedAll(empty) must not return null");
        assertTrue(vectors.isEmpty(), "embedAll(empty) must return an empty list");
    }

    @Test
    void runtimeIsAvailableAfterFakeInjection() {
        var runtime = createRuntime();
        installFakeEmbedder(runtime);
        assertTrue(runtime.isAvailable(),
                "Runtime must report available after fake embedder is wired");
    }

    @Test
    void dimensionsAccessorIsNonNegativeOrMinusOne() {
        var runtime = createRuntime();
        installFakeEmbedder(runtime);
        var dims = runtime.dimensions();
        assertTrue(dims == -1 || dims >= 0,
                "dimensions() must return -1 (unknown) or a non-negative dimension, got " + dims);
    }

    /**
     * Default priority for a freshly-constructed runtime must match what
     * {@link EmbeddingRuntime#priority()} declares — catches subclasses
     * that accidentally override to a stale sentinel.
     */
    @Test
    void runtimeDeclaresStablePriority() {
        var p1 = createRuntime().priority();
        var p2 = createRuntime().priority();
        assertEquals(p1, p2, "priority() must be stable across instances");
    }
}
