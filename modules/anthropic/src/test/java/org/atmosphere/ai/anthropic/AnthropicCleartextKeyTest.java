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
package org.atmosphere.ai.anthropic;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.llm.CleartextApiKeyPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Anthropic client (and the Cohere client, which shares
 * {@code AbstractSseLlmClient}) sends its {@code x-api-key} over plain
 * {@code http} only to a loopback host. Before, {@code LLM_BASE_URL} pointing
 * at any {@code http} proxy got the key in cleartext.
 */
class AnthropicCleartextKeyTest {

    private static final String REMOTE_HTTP = "http://llm.invalid:8000";
    private static final String KEY = "sk-ant-test-not-a-real-key";

    @SuppressWarnings("unchecked")
    private static HttpClient recordingClient() throws IOException, InterruptedException {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("not a real server"));
        return http;
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "claude-sonnet-4-6",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyOverPlainHttpToANonLoopbackHostIsNeverSent() throws Exception {
        var http = recordingClient();
        var client = AnthropicMessagesClient.builder().baseUrl(REMOTE_HTTP).apiKey(KEY)
                .httpClient(http).retryPolicy(org.atmosphere.ai.RetryPolicy.NONE).build();
        var session = new CollectingSession();

        client.stream("claude-sonnet-4-6", List.of(), "be terse", "Hi", context(), session, null);

        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var reason = assertInstanceOf(IllegalStateException.class, session.failure()).getMessage();
        assertTrue(reason.contains(REMOTE_HTTP), reason);
        assertTrue(reason.contains(CleartextApiKeyPolicy.ALLOW_PROPERTY), reason);
        assertEquals(List.of(), client.listModels());
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withTheOptInTheKeyIsSent() throws Exception {
        var http = recordingClient();
        var client = AnthropicMessagesClient.builder().baseUrl(REMOTE_HTTP).apiKey(KEY)
                .httpClient(http).retryPolicy(org.atmosphere.ai.RetryPolicy.NONE)
                .allowCleartextApiKey(true).build();

        client.stream("claude-sonnet-4-6", List.of(), "be terse", "Hi", context(), new CollectingSession(), null);

        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(KEY, sent.getValue().headers().firstValue("x-api-key").orElseThrow());
    }
}
