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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Objects;

/**
 * {@link EmbeddingRuntime} backed by the Spring AI Alibaba-provided
 * {@link EmbeddingModel}. The Alibaba starter exposes Spring AI's standard
 * embedding interface, so the bridge mirrors the core Spring AI adapter while
 * keeping a distinct runtime name for selection and diagnostics.
 */
public class SpringAiAlibabaEmbeddingRuntime implements EmbeddingRuntime {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiAlibabaEmbeddingRuntime.class);

    private static volatile EmbeddingModel staticModel;

    private volatile EmbeddingModel instanceModel;

    /** Spring auto-config hook: supply the configured Alibaba {@link EmbeddingModel}. */
    public static void setEmbeddingModel(EmbeddingModel model) {
        staticModel = model;
    }

    /** Test / programmatic hook: override the injected model on a single instance. */
    public void setNativeEmbeddingModel(EmbeddingModel model) {
        this.instanceModel = model;
    }

    @Override
    public String name() {
        return "spring-ai-alibaba";
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
            return model.dimensions();
        } catch (Exception e) {
            logger.debug("Spring AI Alibaba EmbeddingModel.dimensions() failed, returning -1", e);
            return -1;
        }
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var model = resolve();
        if (model == null) {
            throw new IllegalStateException("Spring AI Alibaba EmbeddingModel not configured");
        }
        return model.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        var model = resolve();
        if (model == null) {
            throw new IllegalStateException("Spring AI Alibaba EmbeddingModel not configured");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        return model.embed(texts);
    }

    @Override
    public int priority() {
        return 200;
    }

    private EmbeddingModel resolve() {
        var local = instanceModel;
        return local != null ? local : staticModel;
    }
}
