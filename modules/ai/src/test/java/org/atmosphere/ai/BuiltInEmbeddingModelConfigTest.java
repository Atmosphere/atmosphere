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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * In {@code llm.mode=local} the Built-in embedding runtime needs a named
 * embedding model, and the runtime the resolver hands out is a fresh
 * {@code ServiceLoader} instance nobody configured — so the instance setter is
 * no way to name it. The model is named by configuration, which the resolved
 * instance reads, and posted to the embeddings endpoint.
 */
class BuiltInEmbeddingModelConfigTest {

    @AfterEach
    void reset() {
        System.clearProperty(AiConfig.EMBEDDING_MODEL_PROPERTY);
        AiConfig.resetForTesting();
        EmbeddingRuntimeResolver.resetCache();
    }

    @Test
    void aLocalChatModelWithANamedEmbeddingModelResolvesAndPostsThatModel() throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var reply = "{\"data\":[{\"embedding\":[0.1,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
        try {
            AiConfig.configure("local", "llama3.2", null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            System.setProperty(AiConfig.EMBEDDING_MODEL_PROPERTY, "nomic-embed-text");
            EmbeddingRuntimeResolver.resetCache();

            var runtime = EmbeddingRuntimeResolver.resolve();

            assertTrue(runtime.isPresent(), "a named embedding model makes the local backend usable");
            assertEquals("built-in", runtime.get().name());
            assertEquals(2, runtime.get().embed("hello").length);
            assertTrue(body.get().contains("\"model\":\"nomic-embed-text\""), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aLocalChatModelWithoutANamedEmbeddingModelResolvesNothing() {
        assumeTrue(System.getenv(AiConfig.EMBEDDING_MODEL_ENV) == null,
                "the environment names an embedding model");
        AiConfig.configure("local", "llama3.2", null, "http://127.0.0.1:1/v1");
        EmbeddingRuntimeResolver.resetCache();

        assertTrue(EmbeddingRuntimeResolver.resolve().isEmpty(),
                "llama3.2 is not an embedding model and nothing names one");
    }
}
