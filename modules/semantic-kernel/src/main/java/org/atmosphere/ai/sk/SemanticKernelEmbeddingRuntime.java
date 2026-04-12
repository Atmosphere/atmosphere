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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.textembedding.Embedding;
import com.microsoft.semantickernel.services.textembedding.TextEmbeddingGenerationService;
import org.atmosphere.ai.EmbeddingRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link EmbeddingRuntime} backed by Semantic Kernel's
 * {@link TextEmbeddingGenerationService} (the {@code String}-typed
 * specialization of {@code EmbeddingGenerationService}). SK's embedding
 * API is reactive — {@code generateEmbeddingsAsync(List<String>)} returns
 * {@code Mono<List<Embedding>>} — so this adapter {@code .block()}s inside
 * the synchronous SPI boundary. Callers that want non-blocking embedding
 * should work directly with the SK service.
 *
 * <p>{@link Embedding#getVector()} returns {@code List<Float>} (not
 * {@code float[]}); the adapter unwraps to the primitive array required
 * by the Atmosphere SPI.</p>
 *
 * <p>The underlying service is injected via
 * {@link #setEmbeddingService(TextEmbeddingGenerationService)} from Spring
 * auto-configuration or programmatic wiring. When no service is set,
 * {@link #isAvailable()} returns {@code false} and
 * {@link java.util.ServiceLoader}-based discovery skips this runtime
 * cleanly.</p>
 */
public class SemanticKernelEmbeddingRuntime implements EmbeddingRuntime {

    private static volatile TextEmbeddingGenerationService staticService;

    private volatile TextEmbeddingGenerationService instanceService;

    /** Spring auto-config hook: supply the configured SK service. */
    public static void setEmbeddingService(TextEmbeddingGenerationService service) {
        staticService = service;
    }

    /** Test / programmatic hook: override the injected service on a single instance. */
    public void setNativeEmbeddingService(TextEmbeddingGenerationService service) {
        this.instanceService = service;
    }

    @Override
    public String name() {
        return "semantic-kernel";
    }

    @Override
    public boolean isAvailable() {
        return resolve() != null;
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var service = resolve();
        if (service == null) {
            throw new IllegalStateException("Semantic Kernel TextEmbeddingGenerationService not configured");
        }
        var embedding = service.generateEmbeddingAsync(text).block();
        if (embedding == null) {
            throw new IllegalStateException("SK generateEmbeddingAsync returned null");
        }
        return toFloatArray(embedding);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            return List.of();
        }
        var service = resolve();
        if (service == null) {
            throw new IllegalStateException("Semantic Kernel TextEmbeddingGenerationService not configured");
        }
        var embeddings = service.generateEmbeddingsAsync(texts).block();
        if (embeddings == null) {
            throw new IllegalStateException("SK generateEmbeddingsAsync returned null");
        }
        var result = new ArrayList<float[]>(embeddings.size());
        for (var embedding : embeddings) {
            result.add(toFloatArray(embedding));
        }
        return List.copyOf(result);
    }

    @Override
    public int priority() {
        return 180;
    }

    private TextEmbeddingGenerationService resolve() {
        var local = instanceService;
        return local != null ? local : staticService;
    }

    private static float[] toFloatArray(Embedding embedding) {
        var vector = embedding.getVector();
        var out = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            out[i] = vector.get(i);
        }
        return out;
    }
}
