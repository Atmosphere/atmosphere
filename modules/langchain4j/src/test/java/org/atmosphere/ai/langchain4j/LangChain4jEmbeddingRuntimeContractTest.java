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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.test.AbstractEmbeddingRuntimeContractTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Concrete TCK subclass for {@link LangChain4jEmbeddingRuntime}. Injects a
 * test-double {@link EmbeddingModel} via
 * {@link LangChain4jEmbeddingRuntime#setNativeEmbeddingModel} so the
 * cross-runtime assertions in {@link AbstractEmbeddingRuntimeContractTest}
 * exercise the LC4j {@code Response<Embedding>} unwrap logic without a
 * live LC4j client.
 */
class LangChain4jEmbeddingRuntimeContractTest extends AbstractEmbeddingRuntimeContractTest {

    @Override
    protected EmbeddingRuntime createRuntime() {
        return new LangChain4jEmbeddingRuntime();
    }

    @Override
    protected void installFakeEmbedder(EmbeddingRuntime runtime) {
        ((LangChain4jEmbeddingRuntime) runtime).setNativeEmbeddingModel(new FakeLc4jEmbeddingModel());
    }

    /**
     * Minimal {@link EmbeddingModel} stub. Wraps its deterministic vector
     * in LC4j's {@code Response<Embedding>} / {@code Response<List<Embedding>>}
     * so the adapter's unwrap path is exercised.
     */
    private static final class FakeLc4jEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<Embedding> embed(String text) {
            var vector = new float[8];
            vector[0] = (float) text.length();
            return Response.from(Embedding.from(vector));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return embed(segment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            var out = new ArrayList<Embedding>(segments.size());
            for (var segment : segments) {
                var vector = new float[8];
                vector[0] = (float) segment.text().length();
                out.add(Embedding.from(vector));
            }
            return Response.from(out);
        }

        @Override
        public int dimension() {
            return 8;
        }
    }
}
