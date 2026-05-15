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
package org.atmosphere.ai.rag.pinecone;

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

class PineconeContextProviderTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "matches": [
                {"id": "doc-1", "score": 0.93, "metadata": {"content": "Atmosphere supports WebSocket.", "source": "docs/transport.md"}},
                {"id": "doc-2", "score": 0.82, "metadata": {"content": "Reconnect uses bounded replay.", "source": "docs/resilience.md"}}
              ],
              "namespace": "docs"
            }""";

    @Test
    @SuppressWarnings("unchecked")
    void retrieveSendsExpectedRequestAndMapsResponse() throws Exception {
        var http = mock(HttpClient.class);
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SAMPLE_RESPONSE);
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = PineconeContextProvider
                .builder("atm-docs-abc.svc.us-east-1.pinecone.io", "key-xyz", new FakeEmbedding())
                .namespace("docs")
                .httpClient(http)
                .build();

        var hits = provider.retrieve("Hello", 4);

        assertEquals(2, hits.size());
        assertEquals("Atmosphere supports WebSocket.", hits.get(0).content());
        assertEquals("docs/transport.md", hits.get(0).source());
        assertEquals(0.93, hits.get(0).score(), 1e-9);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        var sent = captor.getValue();
        assertEquals("POST", sent.method());
        assertEquals("https://atm-docs-abc.svc.us-east-1.pinecone.io/query",
                sent.uri().toString());
        assertEquals("key-xyz", sent.headers().firstValue("Api-Key").orElseThrow());
        assertEquals("2024-10", sent.headers().firstValue("X-Pinecone-API-Version").orElseThrow());
    }

    @Test
    void buildQueryRequestIncludesNamespaceWhenSet() {
        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .namespace("docs")
                .build();

        var body = provider.buildQueryRequest(new float[]{0.1f}, 5);
        assertTrue(body.contains("\"namespace\":\"docs\""), body);
        assertTrue(body.contains("\"topK\":5"), body);
        assertTrue(body.contains("\"includeMetadata\":true"), body);
    }

    @Test
    void buildQueryRequestOmitsNamespaceWhenUnset() {
        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .build();

        var body = provider.buildQueryRequest(new float[]{0.1f}, 5);
        assertTrue(!body.contains("\"namespace\""),
                "default-namespace requests must not splice an empty namespace key");
    }

    @Test
    void parseMatchesSkipsHitsWithoutMetadata() {
        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .build();
        var bodyNoMetadata = "{\"matches\":[{\"id\":\"x\",\"score\":0.5}]}";
        assertTrue(provider.parseMatches(bodyNoMetadata).isEmpty());
    }

    @Test
    void parseMatchesSkipsHitsWithoutConfiguredContentField() {
        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .contentField("text")
                .build();
        var body = "{\"matches\":[{\"id\":\"x\",\"score\":0.5,\"metadata\":{\"content\":\"x\"}}]}";
        assertTrue(provider.parseMatches(body).isEmpty());
    }

    @Test
    void invalidHostRejected() {
        var emb = new FakeEmbedding();
        assertThrows(IllegalArgumentException.class,
                () -> PineconeContextProvider.builder("https://leak.com/path", "k", emb).build(),
                "scheme must be rejected — caller must pass a bare host");
        assertThrows(IllegalArgumentException.class,
                () -> PineconeContextProvider.builder("h.svc.io/admin", "k", emb).build(),
                "path injection must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> PineconeContextProvider.builder("h:443", "k", emb).build(),
                "port specification must be rejected — default 443 is implicit");
        assertThrows(IllegalArgumentException.class,
                () -> PineconeContextProvider.builder("", "k", emb).build());
    }

    @Test
    void retrieveReturnsEmptyForBlankQuery() {
        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .build();
        assertTrue(provider.retrieve(null, 4).isEmpty());
        assertTrue(provider.retrieve("", 4).isEmpty());
        assertTrue(provider.retrieve("ok", 0).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveWithoutSourceFieldLeavesItNull() throws Exception {
        var http = mock(HttpClient.class);
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SAMPLE_RESPONSE);
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .sourceField(null)
                .httpClient(http)
                .build();

        var hits = provider.retrieve("q", 3);
        assertEquals(2, hits.size());
        assertNull(hits.get(0).source());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveReturnsEmptyOnNon2xx() throws Exception {
        var http = mock(HttpClient.class);
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("{\"error\":\"forbidden\"}");
        org.mockito.Mockito.doReturn(response).when(http).send(any(HttpRequest.class), any());

        var provider = PineconeContextProvider
                .builder("h.svc.region.pinecone.io", "k", new FakeEmbedding())
                .httpClient(http)
                .build();

        assertTrue(provider.retrieve("q", 4).isEmpty());
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
