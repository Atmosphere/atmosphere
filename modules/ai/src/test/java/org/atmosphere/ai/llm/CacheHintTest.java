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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheHintTest {

    @Test
    void noneFactoryReturnsDisabledHint() {
        var hint = CacheHint.none();

        assertEquals(CacheHint.CachePolicy.NONE, hint.policy());
        assertFalse(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void conservativeFactoryReturnsEnabledHint() {
        var hint = CacheHint.conservative();

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, hint.policy());
        assertTrue(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void conservativeWithKeyReturnsEnabledHintWithKey() {
        var hint = CacheHint.conservative("my-key");

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, hint.policy());
        assertTrue(hint.enabled());
        assertEquals("my-key", hint.cacheKey().orElse(null));
    }

    @Test
    void conservativeWithNullKeyReturnsEmptyOptional() {
        var hint = CacheHint.conservative(null);

        assertTrue(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
    }

    @Test
    void aggressiveWithKeyReturnsAggressivePolicy() {
        var hint = CacheHint.aggressive("agg-key");

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, hint.policy());
        assertTrue(hint.enabled());
        assertEquals("agg-key", hint.cacheKey().orElse(null));
    }

    @Test
    void enabledReturnsFalseOnlyForNone() {
        assertFalse(CacheHint.none().enabled());
        assertTrue(CacheHint.conservative().enabled());
        assertTrue(CacheHint.aggressive("k").enabled());
    }

    @Test
    void constructorDefaultsNullPolicyToNone() {
        var hint = new CacheHint(null, Optional.of("key"), Optional.of(Duration.ofMinutes(5)));

        assertEquals(CacheHint.CachePolicy.NONE, hint.policy());
        assertFalse(hint.enabled());
    }

    @Test
    void constructorDefaultsNullCacheKeyToEmpty() {
        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE, null, Optional.empty());

        assertTrue(hint.cacheKey().isEmpty());
    }

    @Test
    void constructorDefaultsNullTtlToEmpty() {
        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE, Optional.empty(), null);

        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void constructorPreservesProvidedValues() {
        var ttl = Duration.ofMinutes(10);
        var hint = new CacheHint(CacheHint.CachePolicy.AGGRESSIVE,
                Optional.of("custom-key"), Optional.of(ttl));

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, hint.policy());
        assertEquals("custom-key", hint.cacheKey().orElse(null));
        assertEquals(ttl, hint.ttl().orElse(null));
    }

    @Test
    void metadataKeyIsNotNull() {
        assertFalse(CacheHint.METADATA_KEY.isBlank());
    }

    @Test
    void fromNullContextReturnsNone() {
        var hint = CacheHint.from(null);
        assertFalse(hint.enabled());
    }

    // ------------------------------------------------------------------
    // endpointAcceptsPromptCacheKey — the shared AUTO allow-list (default-DENY)
    // ------------------------------------------------------------------

    @Test
    void endpointAcceptsNullOrBlankIsDefaultDeny() {
        // Default-DENY: an unknown (null/blank) host must NOT receive the field,
        // because a strict OpenAI-compat proxy would reject the whole request.
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey(null),
                "null base URL must default-deny prompt_cache_key");
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey(""),
                "blank base URL must default-deny prompt_cache_key");
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey("   "),
                "whitespace base URL must default-deny prompt_cache_key");
    }

    @Test
    void endpointAcceptsKnownOpenAiHosts() {
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("https://api.openai.com/v1"),
                "api.openai.com must allow prompt_cache_key");
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey(
                        "https://my-resource.openai.azure.com/openai/deployments/gpt-4o"),
                "Azure OpenAI must allow prompt_cache_key");
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("http://localhost:1234/v1"),
                "localhost must allow prompt_cache_key");
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("http://127.0.0.1:8080/v1"),
                "127.0.0.1 must allow prompt_cache_key");
    }

    @Test
    void endpointAcceptsIsCaseInsensitive() {
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("HTTPS://API.OPENAI.COM/V1"),
                "host matching must be case-insensitive");
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("http://LOCALHOST:1234/v1"),
                "host matching must be case-insensitive");
    }

    @Test
    void endpointRejectsGeminiOpenAiCompatSurface() {
        // Gemini's OpenAI-compat endpoint rejects unknown fields with HTTP 400.
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey(
                        "https://generativelanguage.googleapis.com/v1beta/openai"),
                "Gemini OpenAI-compat surface must NOT receive prompt_cache_key");
    }

    @Test
    void endpointRejectsUnknownCustomHostByDefault() {
        // Default-DENY for any host not on the known-supported allow-list —
        // Together, Groq, vLLM, a private gateway, etc. all suppress under AUTO.
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey("https://api.example-provider.com/v1"),
                "an arbitrary custom host must default-deny prompt_cache_key");
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey("https://api.together.xyz/v1"),
                "Together must default-deny prompt_cache_key under AUTO");
    }

    // ------------------------------------------------------------------
    // Mode Parity (Correctness Invariant #7): the Built-in client's AUTO
    // host decision and the shared CacheHint helper consulted by the
    // LangChain4j / Spring AI adapters must AGREE for the same endpoint URL.
    // The Built-in delegates autoSupportsPromptCacheKey() to the helper, so
    // for any URL the two must return the identical boolean.
    // ------------------------------------------------------------------

    @Test
    void builtInAndFrameworkAgreeOnAutoDecisionPerEndpoint() throws Exception {
        Method auto = OpenAiCompatibleClient.class.getDeclaredMethod("autoSupportsPromptCacheKey");
        auto.setAccessible(true);

        for (String url : List.of(
                "https://api.openai.com/v1",
                "https://my-resource.openai.azure.com/openai/deployments/gpt-4o",
                "http://localhost:11434/v1",
                "http://127.0.0.1:8080/v1",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "https://api.example-provider.com/v1",
                "https://api.together.xyz/v1")) {

            var client = OpenAiCompatibleClient.builder()
                    .baseUrl(url)
                    .apiKey("test")
                    .build();
            boolean builtIn = (boolean) auto.invoke(client);
            boolean shared = CacheHint.endpointAcceptsPromptCacheKey(url);

            assertEquals(shared, builtIn,
                    "Built-in AUTO decision must match the shared CacheHint helper for " + url
                            + " (Mode Parity)");
        }

        // A custom host => both false (suppress); api.openai.com => both true.
        var custom = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.example-provider.com/v1").apiKey("test").build();
        assertFalse((boolean) auto.invoke(custom));
        assertFalse(CacheHint.endpointAcceptsPromptCacheKey("https://api.example-provider.com/v1"));

        var openai = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1").apiKey("test").build();
        assertTrue((boolean) auto.invoke(openai));
        assertTrue(CacheHint.endpointAcceptsPromptCacheKey("https://api.openai.com/v1"));
    }
}
