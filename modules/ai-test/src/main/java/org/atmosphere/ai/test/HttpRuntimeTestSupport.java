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
package org.atmosphere.ai.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

/**
 * Shared {@link HttpClient}-mocking support for the hand-rolled HTTP runtime
 * contract tests (Anthropic, Cohere, and any future provider that talks to a
 * direct HTTP endpoint). Centralises the per-invocation request-body sentinel
 * inspection so the happy path and the forced-error path are exercised
 * identically across those adapters, rather than copy-pasting the mock wiring
 * into each {@code *RuntimeContractTest}.
 */
public final class HttpRuntimeTestSupport {

    private HttpRuntimeTestSupport() {
    }

    /**
     * Build a mocked {@link HttpClient} whose {@code send(...)} inspects the
     * outgoing request body: when it contains {@code errorSentinel} it returns
     * a 500 with an error payload (driving the runtime's {@code session.error}
     * path); otherwise it returns {@code statusCode} with {@code body}.
     *
     * @param statusCode   the happy-path HTTP status to return
     * @param body         the happy-path response body (e.g. a canned SSE stream)
     * @param errorSentinel a marker that, when present in the request body,
     *                      forces a 500 error response
     * @return a Mockito-mocked {@link HttpClient}
     */
    @SuppressWarnings("unchecked")
    public static HttpClient mockHttpClient(int statusCode, String body, String errorSentinel) {
        try {
            var httpClient = mock(HttpClient.class);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(inv -> {
                        HttpRequest req = inv.getArgument(0);
                        var requestBody = readBody(req);
                        var response = mock(HttpResponse.class);
                        if (requestBody.contains(errorSentinel)) {
                            when(response.statusCode()).thenReturn(500);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    "{\"error\":\"forced contract error\"}"
                                            .getBytes(StandardCharsets.UTF_8)));
                        } else {
                            when(response.statusCode()).thenReturn(statusCode);
                            when(response.body()).thenReturn(new ByteArrayInputStream(
                                    body.getBytes(StandardCharsets.UTF_8)));
                        }
                        return response;
                    });
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Subscribe to the request's body publisher and accumulate the bytes into a
     * UTF-8 string. Mirrors the wire-level boundary inspection the runtime's
     * real {@link HttpClient} does, so sentinel detection lives at the same
     * layer as the production error-routing logic.
     */
    private static String readBody(HttpRequest req) {
        var publisher = req.bodyPublisher().orElse(null);
        if (publisher == null) {
            return "";
        }
        var collector = new BodyCollector();
        publisher.subscribe(collector);
        return collector.toString();
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            var copy = new byte[item.remaining()];
            item.get(copy);
            out.write(copy, 0, copy.length);
        }

        @Override
        public void onError(Throwable throwable) {
            // Body capture is best-effort for contract testing; partial capture
            // falls through to the happy path rather than crashing the test.
        }

        @Override
        public void onComplete() {
            // No-op — toString() reads whatever has been buffered.
        }

        @Override
        public String toString() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
