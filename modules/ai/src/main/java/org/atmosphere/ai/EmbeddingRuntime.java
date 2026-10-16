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
 * A sibling SPI to {@link AgentRuntime} for text embedding generation. Each AI
 * adapter module ships an implementation backed by the framework's native
 * embedding API (Spring AI {@code EmbeddingModel}, LangChain4j
 * {@code EmbeddingModel}, Koog {@code Embedder}, and others), discovered through
 * the same {@code ServiceLoader} mechanism as {@link AgentRuntime}. A built-in
 * implementation ({@link org.atmosphere.ai.llm.BuiltInEmbeddingRuntime}) posts
 * to an OpenAI-compatible {@code /v1/embeddings} endpoint with no extra adapter
 * on the classpath.
 *
 * <p>The {@code rag} module's context providers resolve their embedding backend
 * through this SPI (see {@link EmbeddingRuntimeResolver}), so RAG pipelines swap
 * providers by changing the adapter on the classpath rather than editing
 * provider-specific code.</p>
 */
public interface EmbeddingRuntime {

    /** @return human-readable name of this runtime ("spring-ai", "lc4j", ...). */
    String name();

    /** @return whether this runtime's native embedding API is on the classpath. */
    boolean isAvailable();

    /**
     * Attach this instance to the application it serves. An
     * {@link AiEnvironment}'s {@link EmbeddingRuntimeRegistry} calls it once,
     * right after creating the instance and before {@link #isAvailable()}.
     * The default does nothing: the runtime keeps reading the static
     * {@link AiConfig} API.
     *
     * @param environment the environment this instance belongs to
     */
    default void attach(AiEnvironment environment) {
    }

    /**
     * Called once: when the {@link AiEnvironment} that loaded this instance
     * closes, or right away on an instance it loaded but does not keep (an
     * unavailable one). Release only what this instance created, such as the
     * HTTP client of {@link org.atmosphere.ai.llm.BuiltInEmbeddingRuntime}; a
     * request already sent should be allowed to finish, a later one may fail.
     * Leave alone what the instance did not create: an embedding model the
     * application bound ({@link AiEnvironment#bindClient}) or offered, and
     * anything shared with another instance. A provider that hands the same
     * instance to several {@code ServiceLoader} lookups keeps the default,
     * which does nothing. A runtime instance the application created itself
     * is never called.
     *
     * <p>Source- and binary-compatible with an implementation written before
     * this hook existed: it inherits the no-op default, and a {@code close()}
     * it declares, with any {@code throws} clause (an implementation that is
     * also {@link AutoCloseable} or {@link java.io.Closeable}), compiles
     * unchanged and is never called by the environment: it stays the
     * application's to call.</p>
     */
    default void onEnvironmentClose() {
    }

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
