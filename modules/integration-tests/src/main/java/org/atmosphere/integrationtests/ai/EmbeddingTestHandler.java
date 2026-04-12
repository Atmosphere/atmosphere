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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Test handler for EmbeddingRuntime SPI wire protocol (Wave 5).
 *
 * <p>Prompt "embed:hello" → embed("hello"), emit vector metadata.</p>
 * <p>Prompt "embedAll:a,bb,ccc" → embedAll(["a","bb","ccc"]), emit batch.</p>
 * <p>Prompt "info" → emit runtime name, available, priority, dimensions.</p>
 */
public class EmbeddingTestHandler implements AtmosphereHandler {

    private final FakeEmbeddingRuntime runtime = new FakeEmbeddingRuntime();

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("embedding-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        if (prompt.startsWith("embed:")) {
            var text = prompt.substring("embed:".length());
            var vector = runtime.embed(text);
            session.sendMetadata("embedding.dimensions", vector.length);
            session.sendMetadata("embedding.vector_0", vector[0]);
        } else if (prompt.startsWith("embedAll:")) {
            var texts = List.of(prompt.substring("embedAll:".length()).split(","));
            var vectors = runtime.embedAll(texts);
            session.sendMetadata("embedding.count", vectors.size());
            for (int i = 0; i < vectors.size(); i++) {
                session.sendMetadata("embedding.vector_" + i + "_0", vectors.get(i)[0]);
            }
        } else if ("info".equals(prompt)) {
            session.sendMetadata("embedding.name", runtime.name());
            session.sendMetadata("embedding.available", runtime.isAvailable());
            session.sendMetadata("embedding.priority", runtime.priority());
            session.sendMetadata("embedding.dimensions", runtime.dimensions());
        }

        session.emit(new AiEvent.Complete(null, Map.of()));
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

    private static class FakeEmbeddingRuntime implements EmbeddingRuntime {
        @Override
        public String name() {
            return "fake-e2e";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int dimensions() {
            return 8;
        }

        @Override
        public float[] embed(String text) {
            var vector = new float[8];
            vector[0] = text.length();
            return vector;
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public int priority() {
            return 100;
        }
    }
}
