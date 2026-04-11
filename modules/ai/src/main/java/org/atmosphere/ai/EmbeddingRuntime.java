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

import java.util.List;

/**
 * Phase 8 of the unified {@code @Agent} API: a sibling SPI to
 * {@link AgentRuntime} for text embedding generation. Each runtime adapter
 * (Spring AI {@code EmbeddingModel}, LangChain4j {@code EmbeddingModel}, ADK
 * {@code Embedder}, Koog {@code Embedder}, Embabel via Spring AI) ships an
 * implementation discovered through the same {@code ServiceLoader} mechanism
 * as {@link AgentRuntime}.
 *
 * <p>The {@code rag} module currently consumes provider-specific embedding
 * APIs directly; Phase 8 lifts that into a runtime-agnostic SPI so RAG
 * pipelines can swap backends without code changes.</p>
 */
public interface EmbeddingRuntime {

    /** @return human-readable name of this runtime ("spring-ai", "lc4j", ...). */
    String name();

    /** @return whether this runtime's native embedding API is on the classpath. */
    boolean isAvailable();

    /**
     * @return embedding dimension produced by the configured model, or {@code -1}
     * when the runtime cannot answer without making a network call.
     */
    default int dimensions() {
        return -1;
    }

    /**
     * Embed a single text into a vector.
     *
     * @param text the input text; must not be null
     * @return the embedding vector
     */
    float[] embed(String text);

    /**
     * Batch variant — preferred when embedding many strings because most
     * providers can amortize the per-request overhead.
     *
     * @param texts the input texts; must not be null
     * @return one embedding vector per input, in the same order
     */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * Optional priority for selection when multiple runtimes are present;
     * higher wins. Mirrors {@link AgentRuntime#priority()}.
     */
    default int priority() {
        return 100;
    }
}
