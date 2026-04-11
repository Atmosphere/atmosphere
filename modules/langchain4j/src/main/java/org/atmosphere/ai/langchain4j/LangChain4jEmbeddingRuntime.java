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

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.atmosphere.ai.EmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link EmbeddingRuntime} backed by LangChain4j's {@link EmbeddingModel}.
 * LC4j wraps its embedding result in {@code Response<Embedding>} and its
 * batch API takes {@code List<TextSegment>}; this adapter unwraps both to
 * the Atmosphere SPI's {@code float[]} and {@code List<float[]>}.
 *
 * <p>The underlying model is injected via {@link #setEmbeddingModel(EmbeddingModel)}
 * from Spring auto-configuration or programmatic wiring. When no model is
 * set, {@link #isAvailable()} returns {@code false} and
 * {@link java.util.ServiceLoader}-based discovery skips this runtime cleanly.</p>
 */
public class LangChain4jEmbeddingRuntime implements EmbeddingRuntime {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jEmbeddingRuntime.class);

    private static volatile EmbeddingModel staticModel;

    private volatile EmbeddingModel instanceModel;

    /** Spring auto-config hook: supply the configured {@link EmbeddingModel}. */
    public static void setEmbeddingModel(EmbeddingModel model) {
        staticModel = model;
    }

    /** Test / programmatic hook: override the injected model on a single instance. */
    public void setNativeEmbeddingModel(EmbeddingModel model) {
        this.instanceModel = model;
    }

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    public boolean isAvailable() {
        return resolve() != null;
    }

    @Override
    public int dimensions() {
        var model = resolve();
        if (model == null) {
            return -1;
        }
        try {
            return model.dimension();
        } catch (Exception e) {
            logger.debug("LC4j EmbeddingModel.dimension() failed, returning -1", e);
            return -1;
        }
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var model = resolve();
        if (model == null) {
            throw new IllegalStateException("LangChain4j EmbeddingModel not configured");
        }
        var response = model.embed(text);
        return response.content().vector();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        var model = resolve();
        if (model == null) {
            throw new IllegalStateException("LangChain4j EmbeddingModel not configured");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        var segments = new ArrayList<TextSegment>(texts.size());
        for (var text : texts) {
            segments.add(TextSegment.from(text));
        }
        var response = model.embedAll(segments);
        var embeddings = response.content();
        var result = new ArrayList<float[]>(embeddings.size());
        for (var embedding : embeddings) {
            result.add(embedding.vector());
        }
        return List.copyOf(result);
    }

    @Override
    public int priority() {
        return 190;
    }

    private EmbeddingModel resolve() {
        var local = instanceModel;
        return local != null ? local : staticModel;
    }
}
