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
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

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
    void streamFallsBackToSendWhenNoTokensEmitted() throws Exception {
        var httpClient = mock(HttpClient.class);

        // First call: streaming request returns 200 but plain JSON (no SSE data: lines)
        var streamResponse = mock(HttpResponse.class);
        when(streamResponse.statusCode()).thenReturn(200);
        when(streamResponse.body()).thenReturn(
                Stream.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

        // Second call: fallback send() returns proper result
        var sendResponse = mock(HttpResponse.class);
        when(sendResponse.statusCode()).thenReturn(200);
        when(sendResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"COMPLETED\"},"
                        + "\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"fallback result\"}]}]}}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(streamResponse)
                .thenReturn(sendResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};

        transport.stream("agent", "search", Map.of("q", "test"),
                tokens::add, () -> completed[0] = true);

        assertTrue(completed[0], "onComplete must be called");
        assertFalse(tokens.isEmpty(), "Fallback to send() should produce tokens");
        assertEquals("fallback result", tokens.getFirst());
    }

    @Test
    void streamDeliversSseTokensDirectly() throws Exception {
        var httpClient = mock(HttpClient.class);
        var streamResponse = mock(HttpResponse.class);
        when(streamResponse.statusCode()).thenReturn(200);
        when(streamResponse.body()).thenReturn(
                Stream.of("data: {\"artifact\":{\"parts\":[{\"text\":\"token1\"}]}}",
                        "data: {\"artifact\":{\"parts\":[{\"text\":\"token2\"}]}}",
                        "data: [DONE]"));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(streamResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};

        transport.stream("agent", "search", Map.of("q", "test"),
                tokens::add, () -> completed[0] = true);

        assertTrue(completed[0]);
        assertEquals(2, tokens.size());
        assertEquals("token1", tokens.get(0));
        assertEquals("token2", tokens.get(1));
    }

    @Test
    void streamFallsBackOnNon200Status() throws Exception {
        var httpClient = mock(HttpClient.class);

        // Streaming returns 500
        var streamResponse = mock(HttpResponse.class);
        when(streamResponse.statusCode()).thenReturn(500);
        when(streamResponse.body()).thenReturn(Stream.of());

        // Fallback send returns success
        var sendResponse = mock(HttpResponse.class);
        when(sendResponse.statusCode()).thenReturn(200);
        when(sendResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"COMPLETED\"},"
                        + "\"artifacts\":[{\"parts\":[{\"type\":\"text\",\"text\":\"sync result\"}]}]}}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(streamResponse)
                .thenReturn(sendResponse);

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a", httpClient);
        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};

        transport.stream("agent", "search", Map.of("q", "test"),
                tokens::add, () -> completed[0] = true);

        assertTrue(completed[0]);
        assertEquals("sync result", tokens.getFirst());
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

    // ---- Agent-card provenance on the consuming side (registre#19) ----

    private static org.atmosphere.a2a.types.AgentCard minimalCard() {
        return new org.atmosphere.a2a.types.AgentCard(
                "remote-agent", "a peer", null, null, "1.0", null,
                null, null, null, null, null, null, null, null);
    }

    private static HttpClient clientReturningCard(String cardJson) throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + cardJson + "}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        return httpClient;
    }

    private static String toJson(Object card) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(card);
    }

    /**
     * Regression: cards were signed in production but verified nowhere —
     * the provenance guarantee was decorative. A signed card that fails
     * self-integrity (tampered after signing) must make the peer
     * unavailable even without a pinned key.
     */
    @Test
    void isAvailableRejectsTamperedSignedCard() throws Exception {
        var signer = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var signed = signer.sign(minimalCard());
        var tamperedJson = toJson(signed).replace("a peer", "an impostor");

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a",
                clientReturningCard(tamperedJson));

        assertFalse(transport.isAvailable(),
                "a signed card altered after signing must fail integrity and "
                + "mark the peer unavailable");
    }

    @Test
    void isAvailableAcceptsIntactSignedCardWithoutPin() throws Exception {
        var signer = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var signed = signer.sign(minimalCard());

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a",
                clientReturningCard(toJson(signed)));

        assertTrue(transport.isAvailable());
    }

    /** A pinned key binds identity: a card signed by any other key fails closed. */
    @Test
    void isAvailableWithPinnedKeyRejectsCardSignedByOtherKey() throws Exception {
        var peerSigner = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var pinnedSigner = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var signed = peerSigner.sign(minimalCard());

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a",
                clientReturningCard(toJson(signed)),
                A2aAgentTransport.Timeouts.DEFAULT, pinnedSigner.publicKey());

        assertFalse(transport.isAvailable(),
                "a pinned key must fail closed against a card signed by a different key");
    }

    @Test
    void isAvailableWithPinnedKeyAcceptsMatchingSignature() throws Exception {
        var signer = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var signed = signer.sign(minimalCard());

        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a",
                clientReturningCard(toJson(signed)),
                A2aAgentTransport.Timeouts.DEFAULT, signer.publicKey());

        assertTrue(transport.isAvailable());
    }

    /** A pinned key also refuses peers that present no card at all. */
    @Test
    void isAvailableWithPinnedKeyRejectsMissingCard() throws Exception {
        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"no such method\"}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var pinned = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        var transport = new A2aAgentTransport("agent", "http://localhost:9999/a2a",
                httpClient, A2aAgentTransport.Timeouts.DEFAULT, pinned.publicKey());

        assertFalse(transport.isAvailable());
    }
}
