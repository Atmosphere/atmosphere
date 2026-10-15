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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code prompt_cache_key} and {@code logprobs} modes belong to the client
 * they were built into: settings configured later in the JVM (another agent,
 * another application, another {@link AiConfig#configure} call) must not change
 * what an already-built client sends.
 */
class OpenAiCompatibleClientBuildTimeModesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENAI = "https://api.openai.com/v1";
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai";

    @AfterEach
    void clearKnobs() {
        System.clearProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.clearProperty(AiConfig.LOGPROBS_PROPERTY);
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void promptCacheKeyDisabledClientStaysDisabledAfterLaterConfigureWithEnabled() throws Exception {
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "disabled");
        var built = AiConfig.configure("remote", "gpt-4o-mini", "test", OPENAI).client();

        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "enabled");
        AiConfig.configure("remote", "gpt-4o-mini", "test", OPENAI);

        var json = MAPPER.readTree(body(built, cacheHinted("gpt-4o-mini")));
        assertFalse(json.has("prompt_cache_key"),
                "a client built with DISABLED must keep suppressing prompt_cache_key after a later "
                        + "configure with ENABLED, got: " + json);
    }

    @Test
    void promptCacheKeyEnabledClientStaysEnabledAfterLaterConfigureWithDisabled() throws Exception {
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "enabled");
        var built = AiConfig.configure("remote", "gemini-2.5-flash", "test", GEMINI).client();

        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "disabled");
        AiConfig.configure("remote", "gemini-2.5-flash", "test", GEMINI);

        var json = MAPPER.readTree(body(built, cacheHinted("gemini-2.5-flash")));
        assertTrue(json.has("prompt_cache_key"),
                "a client built with ENABLED must keep emitting prompt_cache_key after a later "
                        + "configure with DISABLED, got: " + json);
    }

    @Test
    void logprobsDisabledClientStaysDisabledAfterTheKnobChanges() throws Exception {
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "disabled");
        var built = AiConfig.configure("remote", "gpt-4o-mini", "test", OPENAI).client();

        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "enabled");
        AiConfig.configure("remote", "gpt-4o-mini", "test", OPENAI);

        var json = MAPPER.readTree(body(built, logprobsRequested("gpt-4o-mini")));
        assertFalse(json.has("logprobs"),
                "a client built with logprobs DISABLED must keep suppressing the field after the "
                        + "knob is set to ENABLED, got: " + json);
    }

    @Test
    void logprobsEnabledClientStaysEnabledAfterTheKnobChanges() throws Exception {
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "enabled");
        var built = AiConfig.configure("remote", "gemini-2.5-flash", "test", GEMINI).client();

        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "disabled");
        AiConfig.configure("remote", "gemini-2.5-flash", "test", GEMINI);

        var json = MAPPER.readTree(body(built, logprobsRequested("gemini-2.5-flash")));
        assertTrue(json.has("logprobs"),
                "a client built with logprobs ENABLED must keep requesting them after the knob "
                        + "is set to DISABLED, got: " + json);
    }

    @Test
    void builderModesWinOverTheKnobs() throws Exception {
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "enabled");
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "enabled");
        var client = OpenAiCompatibleClient.builder()
                .baseUrl(OPENAI)
                .apiKey("test")
                .promptCacheKeyMode(PromptCacheKeyMode.DISABLED)
                .logprobsMode(LogprobsMode.DISABLED)
                .build();

        assertEquals(PromptCacheKeyMode.DISABLED, client.promptCacheKeyMode());
        assertEquals(LogprobsMode.DISABLED, client.logprobsMode());
        assertFalse(MAPPER.readTree(body(client, cacheHinted("gpt-4o-mini"))).has("prompt_cache_key"));
        assertFalse(MAPPER.readTree(body(client, logprobsRequested("gpt-4o-mini"))).has("logprobs"));
    }

    @Test
    void directlyBuiltClientReadsTheKnobsOnceWhenBuilt() {
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "disabled");
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "enabled");
        var client = OpenAiCompatibleClient.builder().baseUrl(OPENAI).apiKey("test").build();
        System.clearProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.clearProperty(AiConfig.LOGPROBS_PROPERTY);

        assertEquals(PromptCacheKeyMode.DISABLED, client.promptCacheKeyMode());
        assertEquals(LogprobsMode.ENABLED, client.logprobsMode());
        assertEquals(PromptCacheKeyMode.AUTO,
                OpenAiCompatibleClient.builder().baseUrl(OPENAI).build().promptCacheKeyMode(),
                "with no knob set a directly built client stays on AUTO");
    }

    @Test
    void logprobsKnobChangedAfterTheSettingsWereBuiltIsReportedNotInEffect() {
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "disabled");
        AiConfig.configure("remote", "gpt-4o-mini", "test", OPENAI);
        System.setProperty(AiConfig.LOGPROBS_PROPERTY, "enabled");

        assertEquals(List.of(AiConfig.LOGPROBS_PROPERTY), AiConfig.reportKnobsNotInEffect(),
                "the settings' client keeps DISABLED, so the ENABLED knob is not in effect");
    }

    private static ChatCompletionRequest cacheHinted(String model) {
        return ChatCompletionRequest.builder(model)
                .system("You are helpful")
                .user("Hello")
                .cacheHint(CacheHint.conservative("sess-1"))
                .build();
    }

    private static ChatCompletionRequest logprobsRequested(String model) {
        return ChatCompletionRequest.builder(model)
                .system("You are helpful")
                .user("Hello")
                .logprobs(true)
                .build();
    }

    private static String body(LlmClient client, ChatCompletionRequest request) throws Exception {
        Method m = OpenAiCompatibleClient.class.getDeclaredMethod(
                "buildRequestBody", ChatCompletionRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, request);
    }
}
