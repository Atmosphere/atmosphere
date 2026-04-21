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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Built-in {@link OpenAiCompatibleClient} emits a
 * {@code prompt_cache_key} JSON field when the {@link ChatCompletionRequest}
 * carries a {@link CacheHint} with an enabled policy and a resolved key. The
 * wire assertion complements the cross-runtime contract assertion in
 * {@code AbstractAgentRuntimeContractTest.runtimeWithPromptCachingAcceptsCacheHint}
 * — the contract proves the runtime accepts the hint at dispatch; this test
 * proves the byte-level wire serialization actually carries the key.
 */
class OpenAiCompatibleClientCacheHintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String invokeBuildRequestBody(ChatCompletionRequest request) throws Exception {
        return invokeBuildRequestBody(request, "https://api.openai.com/v1");
    }

    private static String invokeBuildRequestBody(ChatCompletionRequest request, String baseUrl) throws Exception {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(baseUrl)
                .apiKey("test")
                .build();
        Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildRequestBody", ChatCompletionRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, request);
    }

    @Test
    void noCacheHintDoesNotEmitPromptCacheKey() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "No CacheHint must not emit prompt_cache_key, got: " + body);
    }

    @Test
    void noneCacheHintDoesNotEmitPromptCacheKey() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.none())
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "Policy=NONE must not emit prompt_cache_key, got: " + body);
    }

    @Test
    void conservativeHintWithKeyEmitsPromptCacheKey() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-42"))
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);

        assertTrue(json.has("prompt_cache_key"),
                "Conservative hint with a key must emit prompt_cache_key, got: " + body);
        assertEquals("sess-42", json.get("prompt_cache_key").stringValue());
    }

    @Test
    void aggressiveHintWithKeyEmitsPromptCacheKey() throws Exception {
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.aggressive("sess-99"))
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);

        assertTrue(json.has("prompt_cache_key"));
        assertEquals("sess-99", json.get("prompt_cache_key").stringValue());
    }

    @Test
    void geminiOpenAiCompatEndpointSuppressesPromptCacheKey() throws Exception {
        // Regression: Gemini's OpenAI-compat endpoint at
        // generativelanguage.googleapis.com/v1beta/openai/ rejects unknown
        // fields with HTTP 400 ("Unknown name 'prompt_cache_key': Cannot find
        // field"), unlike OpenAI itself which silently ignores them. Every
        // AI sample driven against Gemini was breaking on this until the
        // wire layer learned to gate emission by hostname.
        var request = ChatCompletionRequest.builder("gemini-2.5-flash")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-42"))
                .build();

        var body = invokeBuildRequestBody(request,
                "https://generativelanguage.googleapis.com/v1beta/openai");
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "Gemini OpenAI-compat endpoint must not receive prompt_cache_key, got: " + body);
    }

    @Test
    void unknownProviderSuppressesPromptCacheKeyByDefault() throws Exception {
        // Default-deny: when the endpoint is not on the known-supports list,
        // we drop the hint at the wire and rely on ResponseCache for caching.
        // Surprises a stricter OpenAI-compat layer (Together, Groq, etc.)
        // would otherwise reject the whole request.
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-42"))
                .build();

        var body = invokeBuildRequestBody(request, "https://api.example-provider.com/v1");
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "Unknown provider must not receive prompt_cache_key, got: " + body);
    }

    @Test
    void enabledHintWithoutKeyDoesNotEmitPromptCacheKey() throws Exception {
        // A conservative hint without a resolved key is a no-op at the wire
        // level — the Built-in runtime's buildRequest usually fills it from
        // the session id before reaching this layer, but the wire must
        // defend itself against a blank key anyway.
        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE,
                Optional.of(""), Optional.empty());
        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(hint)
                .build();

        var body = invokeBuildRequestBody(request);
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "Blank cache key must not emit prompt_cache_key, got: " + body);
    }
}
