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
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Concrete TCK subclass for {@link SemanticKernelEmbeddingRuntime}. Injects
 * a test-double {@link TextEmbeddingGenerationService} whose async methods
 * wrap synchronously-computed vectors in {@link Mono#just(Object)} — the
 * adapter's {@code .block()} returns immediately. Exercises the
 * {@code Embedding} → {@code float[]} unwrap path and the
 * {@code Mono<List<Embedding>>} batch unwrap.
 */
class SemanticKernelEmbeddingRuntimeContractTest extends AbstractEmbeddingRuntimeContractTest {

    @Override
    protected EmbeddingRuntime createRuntime() {
        return new SemanticKernelEmbeddingRuntime();
    }

    @Override
    protected void installFakeEmbedder(EmbeddingRuntime runtime) {
        ((SemanticKernelEmbeddingRuntime) runtime).setNativeEmbeddingService(new TestSkEmbeddingService());
    }

    /**
     * Minimal {@link TextEmbeddingGenerationService} stub. Returns an
     * {@link Embedding} whose {@code getVector()} list's first element
     * equals the input text length, satisfying the base assertion's
     * invariant. Other abstract methods return null/empty since the
     * contract tests don't exercise them.
     */
    private static final class TestSkEmbeddingService implements TextEmbeddingGenerationService {
        @Override
        public Mono<Embedding> generateEmbeddingAsync(String value) {
            return Mono.just(lengthEmbedding(value));
        }

        @Override
        public Mono<List<Embedding>> generateEmbeddingsAsync(List<String> values) {
            var out = new ArrayList<Embedding>(values.size());
            for (var v : values) {
                out.add(lengthEmbedding(v));
            }
            return Mono.just(out);
        }

        @Override
        public String getModelId() {
            return "test-sk-embedding";
        }

        @Override
        public String getServiceId() {
            return "test-sk-service";
        }

        private static Embedding lengthEmbedding(String text) {
            var vector = new ArrayList<Float>(8);
            vector.add((float) text.length());
            for (int i = 1; i < 8; i++) {
                vector.add(0.0f);
            }
            return new Embedding(vector);
        }
    }
}
