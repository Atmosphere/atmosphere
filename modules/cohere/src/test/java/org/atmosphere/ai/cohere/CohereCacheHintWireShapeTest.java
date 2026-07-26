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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.ai.test.HttpRuntimeTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime-truth pin for prompt caching on the Cohere adapter (Correctness
 * Invariant #5): the {@code PROMPT_CACHING} capability declaration and the
 * bytes {@link CohereChatClient} actually puts on the v2 Chat wire must agree,
 * in <em>both</em> directions.
 *
 * <p><b>Why the assertion is conditional rather than "assert the hint appears".</b>
 * As of this commit Cohere deliberately does <em>not</em> declare
 * {@code PROMPT_CACHING}: {@code CohereAgentRuntime.capabilities()} documents
 * that the v2 Chat API publishes no prompt-caching wire shape — no
 * {@code cache_control} block, no ephemeral marker, no top-level TTL — so
 * declaring it from a {@link CacheHint} the client cannot act on would be a
 * capability lie. A test asserting "the hint materializes on the wire" would
 * therefore be asserting a behaviour that does not exist.</p>
 *
 * <p>What is worth pinning is the <em>invariant that survives either
 * decision</em>: a {@code CacheHint} on the context must never produce a cache
 * directive the runtime does not advertise, and a runtime that advertises
 * caching must never silently drop the hint. When Cohere ships a caching wire
 * shape, the implementer flips {@code capabilities()} (which
 * {@code CohereRuntimeContractTest.expectedCapabilities()} already forces them
 * to touch) and this test switches to demanding the directive — no assertion
 * edit needed, and shipping one half without the other fails here.</p>
 */
class CohereCacheHintWireShapeTest {

    /**
     * Tokens that would indicate a prompt-cache directive on the request body.
     * Deliberately specific: a bare {@code "cache"} substring would collide
     * with unrelated field names, and a false positive here would read as
     * "caching is wired" when nothing is.
     */
    private static final List<String> CACHE_DIRECTIVE_TOKENS = List.of(
            "cache_control", "prompt_cache_key", "\"cache\"", "cache_ttl");

    private static final String TEXT_RESPONSE = """
            data: {"type":"message-start","id":"msg_1"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"Hi"}}}}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE"}}

            """;

    @Test
    void cacheHintAndDeclaredCapabilityAgreeOnTheWire() throws Exception {
        var body = streamWithCacheHint(CacheHint.aggressive("cohere-cache-test"));
        var declared = new CohereAgentRuntime().capabilities()
                .contains(AiCapability.PROMPT_CACHING);
        var emitted = CACHE_DIRECTIVE_TOKENS.stream().filter(body::contains).toList();

        if (declared) {
            assertFalse(emitted.isEmpty(),
                    "cohere declares PROMPT_CACHING but an enabled CacheHint produced no cache "
                            + "directive on the v2 Chat body — the capability would be advertising "
                            + "a code path that does not exist (Correctness Invariant #5).\n  body: "
                            + body);
        } else {
            assertTrue(emitted.isEmpty(),
                    "cohere does NOT declare PROMPT_CACHING yet the v2 Chat body carries "
                            + emitted + " — either the capability declaration is now stale (add "
                            + "PROMPT_CACHING to capabilities() and to "
                            + "CohereRuntimeContractTest.expectedCapabilities()) or a cache "
                            + "directive is being emitted that the adapter cannot honor.\n  body: "
                            + body);
        }
    }

    @Test
    void cacheHintDoesNotDisturbTheRestOfTheRequest() throws Exception {
        var withHint = streamWithCacheHint(CacheHint.aggressive("cohere-cache-test"));
        var withoutHint = streamWithCacheHint(null);

        // The hint is metadata, not prompt content: whatever the adapter does
        // with it, the user turn must reach the wire unchanged. A regression
        // that stitched cache metadata into the message would show up here.
        assertTrue(withHint.contains("\"Hello, cached.\""),
                "the user message must reach the wire intact alongside a CacheHint: " + withHint);
        assertFalse(withHint.contains("cohere-cache-test"),
                "the cache key is a caller-side hint, not prompt content — it must never be "
                        + "serialized into the message body as text: " + withHint);
        assertTrue(withoutHint.contains("\"Hello, cached.\""),
                "control: the same request without a hint carries the same user message: "
                        + withoutHint);
    }

    // -- helpers --

    /**
     * Drive one streaming round through the real {@link CohereChatClient} and
     * return the serialized request body.
     *
     * @param hint the {@link CacheHint} to place in the context metadata, or
     *             {@code null} for the no-hint control
     */
    @SuppressWarnings("unchecked")
    private static String streamWithCacheHint(CacheHint hint) throws Exception {
        var httpClient = mockSingleResponse();
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var metadata = hint == null
                ? Map.<String, Object>of()
                : Map.<String, Object>of(CacheHint.METADATA_KEY, hint);
        var context = new AgentExecutionContext(
                "Hello, cached.", "You are helpful", "command-a-plus-05-2026",
                null, "session-cache", "user-1", "conv-cache",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
        var session = new CollectingSession();

        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return HttpRuntimeTestSupport.requestBodyOf(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockSingleResponse() throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                TEXT_RESPONSE.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }

    /** Minimal session that keeps the streamed text so the round can finish. */
    private static final class CollectingSession implements StreamingSession {
        private final List<String> chunks = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        @Override public String sessionId() { return "cohere-cache-test"; }
        @Override public void send(String text) { chunks.add(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
