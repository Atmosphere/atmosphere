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
package org.atmosphere.ai.spring.alibaba;

import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

class SpringAiAlibabaEmbeddingRuntimeContractTest extends AbstractEmbeddingRuntimeContractTest {

    @Override
    protected EmbeddingRuntime createRuntime() {
        return new SpringAiAlibabaEmbeddingRuntime();
    }

    @Override
    protected void installFakeEmbedder(EmbeddingRuntime runtime) {
        ((SpringAiAlibabaEmbeddingRuntime) runtime).setNativeEmbeddingModel(new FakeEmbeddingModel());
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
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
                    "Spring AI Alibaba contract tests exercise embed(String) + embed(List), not call()");
        }

        @Override
        public int dimensions() {
            return 8;
        }
    }
}
