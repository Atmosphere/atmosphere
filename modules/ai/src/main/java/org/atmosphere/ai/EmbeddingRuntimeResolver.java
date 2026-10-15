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

import org.atmosphere.ai.llm.BuiltInEmbeddingRuntime;

import java.util.List;
import java.util.Optional;

/**
 * Resolves {@link EmbeddingRuntime} implementations from the classpath via
 * {@link ServiceLoader}. Mirrors the resolver pattern used for
 * {@link AgentRuntime} so callers get the same priority + availability
 * semantics for embedding adapters.
 *
 * <p>Results are cached after first resolution — the classpath does not
 * change at runtime so the discovered runtimes are stable.</p>
 *
 * <p>The statics resolve the process default's runtimes
 * ({@link AiEnvironment#processDefault()}); an application's own are
 * {@link AiEnvironment#embeddings()}.</p>
 */
public final class EmbeddingRuntimeResolver {

    private EmbeddingRuntimeResolver() {}

    /**
     * Returns the highest-priority available runtime, or
     * {@link Optional#empty()} when no runtime has a wired native
     * {@code EmbeddingModel} (Built-in is reported only when
     * {@link AiConfig} carries a baseUrl, a reachable backend and an
     * embedding model it can name — in {@code llm.mode=local} that means
     * {@code LLM_EMBEDDING_MODEL} / {@code atmosphere.ai.embedding-model} or an
     * embedding-model {@code LLM_MODEL}; otherwise it is excluded from the
     * result).
     */
    public static Optional<EmbeddingRuntime> resolve() {
        var all = resolveAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    /**
     * Returns all available runtimes sorted by priority (highest first).
     * Unlike {@link AgentRuntimeResolver#resolveAll()}, this may return an
     * empty list when no runtime is ready — there is no always-available
     * fallback because {@link BuiltInEmbeddingRuntime} depends on
     * {@link AiConfig} being configured. An empty result is not cached: if
     * resolution fires before the settings exist (startup race), Built-in
     * reports unavailable and the next call rescans.
     */
    public static List<EmbeddingRuntime> resolveAll() {
        return AiEnvironment.processDefault().embeddings().resolveAll();
    }

    /**
     * TCK helper: clear the resolver's cached {@code ServiceLoader} result so
     * the next {@link #resolveAll()} call rescans the classpath. Exists
     * because the cross-module contract tests in {@code modules/ai-test}
     * install and uninstall fake runtimes between assertions and cannot reach
     * a package-private helper from another Maven module without a
     * {@code module-info} export hack. Production code has no legitimate
     * reason to call this — the classpath is immutable at runtime.
     */
    public static void resetCache() {
        AiEnvironment.resetEmbeddingCaches();
    }
}
