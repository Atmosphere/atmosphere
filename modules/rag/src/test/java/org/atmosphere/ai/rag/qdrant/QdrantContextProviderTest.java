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
package org.atmosphere.ai.rag.qdrant;

import org.atmosphere.ai.EmbeddingRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantContextProviderTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "result": [
                {"id": 1, "score": 0.92, "payload": {"content": "first chunk", "source": "a.md"}},
                {"id": 2, "score": 0.81, "payload": {"content": "second chunk", "source": "b.md"}}
              ],
              "status": "ok",
              "time": 0.01
            }""";

    @Test
    @SuppressWarnings("unchecked")
    void retrieveSendsExpectedRequestAndMapsResponse() throws Exception {
        var http = mock(HttpClient.class);
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SAMPLE_RESPONSE);
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = QdrantContextProvider
                .builder("https://qdrant.example.com:6333", "atmosphere_docs", new FakeEmbedding())
                .apiKey("secret-key")
                .httpClient(http)
                .build();

        var hits = provider.retrieve("hello", 4);

        assertEquals(2, hits.size());
        assertEquals("first chunk", hits.get(0).content());
        assertEquals("a.md", hits.get(0).source());
        assertEquals(0.92, hits.get(0).score(), 1e-9);
        assertEquals("second chunk", hits.get(1).content());
        assertEquals(0.81, hits.get(1).score(), 1e-9);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        var sent = captor.getValue();
        assertEquals("POST", sent.method());
        assertTrue(sent.uri().toString()
                        .endsWith("/collections/atmosphere_docs/points/search"),
                "URI must target the encoded collection path: " + sent.uri());
        assertTrue(sent.headers().firstValue("api-key")
                .orElse("").equals("secret-key"));
        assertTrue(sent.headers().firstValue("Content-Type")
                .orElse("").equals("application/json"));
    }

    @Test
    void parseHitsHandlesMissingPayload() {
        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .build();

        var bodyWithoutPayload = "{\"result\":[{\"id\":1,\"score\":0.5}],\"status\":\"ok\"}";
        assertTrue(provider.parseHits(bodyWithoutPayload).isEmpty(),
                "hits without payload should be skipped, not crash the call");
    }

    @Test
    void parseHitsHandlesMissingContentField() {
        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .contentField("text")
                .build();

        var bodyWrongContentField = """
                {"result":[{"id":1,"score":0.5,"payload":{"content":"only-default-name","source":"x.md"}}],"status":"ok"}""";
        assertTrue(provider.parseHits(bodyWrongContentField).isEmpty(),
                "hits whose payload does not carry the configured contentField are skipped");
    }

    @Test
    void buildSearchRequestEncodesVectorAsArray() {
        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .build();

        var body = provider.buildSearchRequest(new float[]{0.1f, -0.5f}, 7);
        assertTrue(body.contains("\"vector\""), body);
        assertTrue(body.contains("0.1"), body);
        assertTrue(body.contains("-0.5"), body);
        assertTrue(body.contains("\"limit\":7"), body);
        assertTrue(body.contains("\"with_payload\":true"), body);
    }

    @Test
    void retrieveReturnsEmptyForBlankQuery() {
        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .build();

        assertTrue(provider.retrieve(null, 4).isEmpty());
        assertTrue(provider.retrieve(" ", 4).isEmpty());
        assertTrue(provider.retrieve("ok", 0).isEmpty());
    }

    @Test
    void invalidCollectionRejected() {
        var ds = new FakeEmbedding();
        assertThrows(IllegalArgumentException.class,
                () -> QdrantContextProvider.builder("http://localhost:6333", "bad/name", ds).build());
        assertThrows(IllegalArgumentException.class,
                () -> QdrantContextProvider.builder("http://localhost:6333", "bad name", ds).build());
        assertThrows(IllegalArgumentException.class,
                () -> QdrantContextProvider.builder("http://localhost:6333", "../leak", ds).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveReturnsEmptyOnNon2xx() throws Exception {
        var http = mock(HttpClient.class);
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("{\"status\":\"error\"}");
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .httpClient(http)
                .build();

        assertTrue(provider.retrieve("q", 3).isEmpty());
    }

    @Test
    void retrieveWithoutSourceFieldLeavesItNull() throws Exception {
        @SuppressWarnings("unchecked")
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SAMPLE_RESPONSE);
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = QdrantContextProvider
                .builder("http://localhost:6333", "kb", new FakeEmbedding())
                .sourceField(null)
                .httpClient(http)
                .build();

        var hits = provider.retrieve("q", 3);
        assertEquals(2, hits.size());
        assertNull(hits.get(0).source());
        assertNull(hits.get(1).source());
    }

    private static final class FakeEmbedding implements EmbeddingRuntime {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }
}
