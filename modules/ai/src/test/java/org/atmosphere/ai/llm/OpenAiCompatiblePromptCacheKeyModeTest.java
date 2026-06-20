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
import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the explicit tri-state {@link PromptCacheKeyMode} override on the
 * Built-in {@link OpenAiCompatibleClient} wire path. Complements
 * {@code OpenAiCompatibleClientCacheHintTest}, which pins the AUTO (legacy
 * host-heuristic) behavior: this class proves that an explicit ENABLED/DISABLED
 * mode overrides the host heuristic deterministically.
 *
 * <p>The mode is read from the framework-wide {@link AiConfig} singleton, so
 * each test configures it via the {@code atmosphere.ai.prompt-cache-key}
 * system property and {@link AiConfig#configure}. The {@link AfterEach} clears
 * the property and re-resolves AiConfig to its AUTO default so no state leaks
 * between tests (each test class also runs in its own Surefire fork:
 * {@code reuseForks=false}).</p>
 */
class OpenAiCompatiblePromptCacheKeyModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetConfig() {
        System.clearProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        // Re-resolve AiConfig to a known AUTO state so a leftover non-null
        // singleton cannot influence an unrelated test that runs after this
        // one in the same fork.
        AiConfig.configure("local", "llama3.2", null, null);
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
    void disabledSuppressesPromptCacheKeyOnOpenAiHost() throws Exception {
        // On api.openai.com the AUTO heuristic WOULD emit prompt_cache_key
        // (allow-list match). An explicit DISABLED must force-suppress it.
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "disabled");
        AiConfig.configure("remote", "gpt-4o-mini", "test", "https://api.openai.com/v1");

        var request = ChatCompletionRequest.builder("gpt-4o-mini")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-42"))
                .build();

        var body = invokeBuildRequestBody(request, "https://api.openai.com/v1");
        var json = MAPPER.readTree(body);

        assertFalse(json.has("prompt_cache_key"),
                "DISABLED mode must suppress prompt_cache_key even on api.openai.com, got: " + body);
    }

    @Test
    void enabledEmitsPromptCacheKeyOnUnknownHost() throws Exception {
        // On a Gemini / unknown host the AUTO heuristic WOULD suppress
        // prompt_cache_key (allow-list miss / default-DENY). An explicit
        // ENABLED must force-emit it.
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "enabled");
        var endpoint = "https://generativelanguage.googleapis.com/v1beta/openai";
        AiConfig.configure("remote", "gemini-2.5-flash", "test", endpoint);

        var request = ChatCompletionRequest.builder("gemini-2.5-flash")
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-99"))
                .build();

        var body = invokeBuildRequestBody(request, endpoint);
        var json = MAPPER.readTree(body);

        assertTrue(json.has("prompt_cache_key"),
                "ENABLED mode must emit prompt_cache_key even on a Gemini/unknown host, got: " + body);
    }
}
