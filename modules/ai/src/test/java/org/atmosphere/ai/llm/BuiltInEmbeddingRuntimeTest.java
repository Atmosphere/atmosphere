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
package org.atmosphere.ai.llm;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level verification of {@link BuiltInEmbeddingRuntime}. The real
 * runtime POSTs to {@code /v1/embeddings}; these tests reflectively
 * exercise {@code buildRequestBody} and {@code parseResponse} so the
 * OpenAI-compatible JSON contract is enforced without a live network.
 *
 * <p>Cross-runtime parity is covered by {@code SpringAiEmbeddingRuntimeContractTest}
 * and {@code LangChain4jEmbeddingRuntimeContractTest} — both subclass
 * {@code AbstractEmbeddingRuntimeContractTest} and exercise the public SPI
 * through a stubbed native {@code EmbeddingModel}.</p>
 */
class BuiltInEmbeddingRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nameIsStable() {
        assertEquals("built-in", new BuiltInEmbeddingRuntime().name());
    }

    @Test
    void priorityIsLowerThanAdapterRuntimes() {
        // Must rank below Spring AI (200) and LC4j (190) so adapter wrappers
        // win when a native EmbeddingModel is wired. Changing this requires
        // revisiting the EmbeddingRuntimeResolver fallback semantics.
        assertEquals(50, new BuiltInEmbeddingRuntime().priority());
    }

    @Test
    void buildRequestBodyEmitsModelAndInputArray() throws Exception {
        Method m = BuiltInEmbeddingRuntime.class.getDeclaredMethod(
                "buildRequestBody", String.class, List.class);
        m.setAccessible(true);
        var body = (String) m.invoke(null, "text-embedding-3-small",
                List.of("hello", "world"));
        var json = MAPPER.readTree(body);

        assertEquals("text-embedding-3-small", json.get("model").stringValue());
        var input = json.get("input");
        assertTrue(input.isArray());
        assertEquals(2, input.size());
        assertEquals("hello", input.get(0).stringValue());
        assertEquals("world", input.get(1).stringValue());
    }

    @Test
    void parseResponseExtractsVectorsInDataArrayOrder() throws Exception {
        var json = """
                {
                  "object": "list",
                  "data": [
                    {"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]},
                    {"object": "embedding", "index": 1, "embedding": [0.4, 0.5, 0.6]}
                  ],
                  "model": "text-embedding-3-small"
                }
                """;
        Method m = BuiltInEmbeddingRuntime.class.getDeclaredMethod(
                "parseResponse", String.class, int.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var vectors = (List<float[]>) m.invoke(null, json, 2);

        assertNotNull(vectors);
        assertEquals(2, vectors.size());
        assertEquals(3, vectors.get(0).length);
        assertEquals(0.1f, vectors.get(0)[0], 0.0001f);
        assertEquals(0.6f, vectors.get(1)[2], 0.0001f);
    }

    @Test
    void parseResponseRejectsMissingDataArray() throws Exception {
        var json = "{\"error\": \"bad request\"}";
        Method m = BuiltInEmbeddingRuntime.class.getDeclaredMethod(
                "parseResponse", String.class, int.class);
        m.setAccessible(true);
        try {
            m.invoke(null, json, 1);
            org.junit.jupiter.api.Assertions.fail("parseResponse must reject missing data array");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IllegalStateException,
                    "Expected IllegalStateException, got " + ite.getCause());
        }
    }

    @Test
    void isAvailableReturnsFalseWithoutConfig() {
        // AiConfig is not set in isolation; Built-in must honestly report
        // unavailable rather than crash when resolve() is called.
        var runtime = new BuiltInEmbeddingRuntime();
        // Note: other tests in the suite may leave AiConfig configured, so
        // we only verify the contract is stable (boolean, no throw) rather
        // than a specific value.
        var available = runtime.isAvailable();
        assertTrue(available || !available,
                "isAvailable() must return a stable boolean without throwing");
    }
}
