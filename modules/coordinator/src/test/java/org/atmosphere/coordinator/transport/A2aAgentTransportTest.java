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
package org.atmosphere.coordinator.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked") // HttpResponse generic type erasure in mock setup
public class A2aAgentTransportTest {

    @Test
    void sendReturnsFailureOnNon200Response() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "search", Map.of("q", "test"));

        assertFalse(result.success());
        assertTrue(result.text().contains("HTTP 500"));
    }

    @Test
    void sendReturnsFailureOnJsonRpcError() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "search", Map.of("q", "test"));

        assertFalse(result.success());
        assertEquals("Invalid request", result.text());
    }

    @Test
    void sendReturnsFailureOnFailedTaskStatus() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"failed\",\"message\":\"Agent crashed\"}}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "search", Map.of("q", "test"));

        assertFalse(result.success());
        assertEquals("Agent crashed", result.text());
    }

    @Test
    void sendReturnsFailureOnCanceledTaskStatus() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"canceled\"}}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "search", Map.of("q", "test"));

        assertFalse(result.success());
        assertEquals("Task canceled", result.text());
    }

    @Test
    void sendReturnsFailureOnUppercaseFailedStatus() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"FAILED\",\"message\":\"boom\"}}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "skill", Map.of());

        assertFalse(result.success());
        assertEquals("boom", result.text());
    }

    @Test
    void isAvailableReturnsFalseOnConnectionError() throws Exception {
        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        assertFalse(transport.isAvailable());
    }

    @Test
    void isAvailableReturnsTrueOn200() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        assertTrue(transport.isAvailable());
    }

    @Test
    void sendReturnsSuccessWithArtifactText() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"COMPLETED\"},"
                        + "\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"search results here\"}]}]}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var result = transport.send("agent", "search", Map.of("q", "test"));

        assertTrue(result.success());
        assertEquals("search results here", result.text());
    }
}
