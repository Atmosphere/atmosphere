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
     * Release what this instance created for itself, such as the HTTP client
     * of {@link org.atmosphere.ai.llm.BuiltInEmbeddingRuntime}. The
     * {@link AiEnvironment} that loaded the instance calls it when it closes,
     * and right away on an instance it loaded but does not keep (an unavailable
     * one). A request already sent should be allowed to finish; a later one
     * may fail. It must leave alone what the instance did not create: an
     * embedding model the application bound ({@link AiEnvironment#bindClient})
     * or offered, and anything shared with another instance. A provider that
     * hands the same instance to several {@code ServiceLoader} lookups keeps
     * the default, which does nothing. Calling it again does nothing.
     *
     * <p>Compatibility with an implementation written before this method
     * existed: one that declares its own {@code public void close()} (to
     * release its resources itself, say) is now called by the environment
     * without asking, on every resolution of every environment, the process
     * default included, for each instance loaded and not kept, and on
     * environment close for each instance handed out. A {@code close()} that
     * releases something static or shared with other instances must move that
     * release out of {@code close()}. One that declares
     * {@code close() throws Exception} (an implementation that is also
     * {@link AutoCloseable}) no longer compiles against this interface until
     * the {@code throws} clause is dropped; already compiled, a checked
     * exception it throws is logged and does not stop the close of the
     * others.</p>
     */
    default void close() {
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
