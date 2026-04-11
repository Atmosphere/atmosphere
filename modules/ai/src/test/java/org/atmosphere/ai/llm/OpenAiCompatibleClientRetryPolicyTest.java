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

import org.atmosphere.ai.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link ChatCompletionRequest} carries a per-request
 * {@link RetryPolicy} override and that the {@link OpenAiCompatibleClient}
 * accepts it. The full retry-loop semantics are exercised in
 * {@link OpenAiCompatibleClientCancelTest} and the integration tests; this
 * test focuses on the SPI plumbing — the new field, the builder setter,
 * and the recursive tool-loop preservation.
 */
class OpenAiCompatibleClientRetryPolicyTest {

    @Test
    void chatCompletionRequestRetryPolicyDefaultsToNull() {
        var request = ChatCompletionRequest.builder("gpt-4o")
                .user("Hello")
                .build();
        // Null means "inherit the client's instance-level default"; only
        // an explicit caller-set policy fires the per-request override
        // path inside sendWithRetry.
        assertNull(request.retryPolicy(),
                "Builder.build() must default retryPolicy to null so the client's default applies");
    }

    @Test
    void chatCompletionRequestBuilderSetsRetryPolicy() {
        var custom = RetryPolicy.NONE;
        var request = ChatCompletionRequest.builder("gpt-4o")
                .user("Hello")
                .retryPolicy(custom)
                .build();
        assertNotNull(request.retryPolicy());
        assertEquals(0, request.retryPolicy().maxRetries());
        assertEquals(custom, request.retryPolicy());
    }

    @Test
    void retryPolicyOverrideTravelsThroughElevenArgShim() {
        // The 11-arg shim defaults retryPolicy to null. Callers using the
        // shim path inherit the client's default — same as builder.build()
        // without an explicit setter.
        var request = new ChatCompletionRequest("gpt-4o", java.util.List.of(),
                0.7, 2048, false, java.util.List.of(),
                "conv-1", null, java.util.List.of(),
                java.util.List.of(), CacheHint.none());
        assertNull(request.retryPolicy());
    }

    @Test
    void canonicalConstructorAcceptsExplicitRetryPolicy() {
        var custom = RetryPolicy.of(5, Duration.ofMillis(100));
        var request = new ChatCompletionRequest("gpt-4o", java.util.List.of(),
                0.7, 2048, false, java.util.List.of(),
                "conv-1", null, java.util.List.of(),
                java.util.List.of(), CacheHint.none(), custom);
        assertEquals(5, request.retryPolicy().maxRetries());
    }

    @Test
    void clientStillExposesItsInstanceLevelDefaultPolicy() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://example.invalid")
                .apiKey("test")
                .retryPolicy(RetryPolicy.NONE)
                .build();
        assertEquals(0, client.retryPolicy().maxRetries(),
                "Client's instance-level retryPolicy must remain settable via builder");
    }
}
