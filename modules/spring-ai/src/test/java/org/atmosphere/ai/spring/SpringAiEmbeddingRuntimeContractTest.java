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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/**
 * Concrete TCK subclass for {@link SpringAiEmbeddingRuntime}. Injects a
 * test-double {@link EmbeddingModel} via {@link SpringAiEmbeddingRuntime#setNativeEmbeddingModel}
 * so the cross-runtime assertions in {@link AbstractEmbeddingRuntimeContractTest}
 * exercise the Spring AI wrapper without a live Spring AI ChatModel.
 */
class SpringAiEmbeddingRuntimeContractTest extends AbstractEmbeddingRuntimeContractTest {

    @Override
    protected EmbeddingRuntime createRuntime() {
        return new SpringAiEmbeddingRuntime();
    }

    @Override
    protected void installFakeEmbedder(EmbeddingRuntime runtime) {
        ((SpringAiEmbeddingRuntime) runtime).setNativeEmbeddingModel(new FakeSpringEmbeddingModel());
    }

    /**
     * Minimal {@link EmbeddingModel} stub: {@code embed(String)} returns a
     * vector whose first element equals the input length, satisfying the
     * base assertion's invariant. Batch calls delegate to the single
     * method. Other abstract methods throw because the contract tests
     * don't exercise them.
     */
    private static final class FakeSpringEmbeddingModel implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            var vector = new float[8];
            vector[0] = (float) text.length();
            return vector;
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return embed(document.getText());
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException(
                    "Spring AI contract tests exercise embed(String) + embed(List), not call()");
        }

        @Override
        public int dimensions() {
            return 8;
        }
    }
}
