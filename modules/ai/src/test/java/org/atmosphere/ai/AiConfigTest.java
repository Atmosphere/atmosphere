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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AiConfigTest {

    @Test
    public void testResolveBaseUrlLocal() {
        assertEquals(AiConfig.OLLAMA_ENDPOINT, AiConfig.resolveBaseUrl("local", null, "llama3.2"));
    }

    @Test
    public void testResolveBaseUrlExplicitWins() {
        assertEquals("https://custom.api/v1", AiConfig.resolveBaseUrl("remote", "https://custom.api/v1", "any"));
    }

    @Test
    public void testResolveBaseUrlGeminiDefault() {
        assertEquals(AiConfig.GEMINI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "gemini-2.5-flash"));
    }

    @Test
    public void testResolveBaseUrlOpenAiGpt() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "gpt-4o"));
    }

    @Test
    public void testResolveBaseUrlOpenAiO1() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "o1-mini"));
    }

    @Test
    public void testResolveBaseUrlOpenAiO3() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "o3"));
    }

    @Test
    public void testResolveBaseUrlBlankExplicit() {
        assertEquals(AiConfig.GEMINI_ENDPOINT, AiConfig.resolveBaseUrl("remote", "", "gemini-2.5-flash"));
    }

    @Test
    public void testConfigureSetsInstance() {
        var settings = AiConfig.configure("local", "llama3.2", null, null);

        assertNotNull(settings);
        assertEquals("llama3.2", settings.model());
        assertEquals("local", settings.mode());
        assertTrue(settings.isLocal());
        assertEquals(AiConfig.OLLAMA_ENDPOINT, settings.baseUrl());
        assertSame(AiConfig.get(), settings);
    }

    @Test
    public void testConfigureRemoteNotLocal() {
        var settings = AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);

        assertFalse(settings.isLocal());
        assertEquals(AiConfig.GEMINI_ENDPOINT, settings.baseUrl());
    }

    @Test
    public void testDefaultConstants() {
        assertEquals("gemini-2.5-flash", AiConfig.DEFAULT_MODEL);
        assertEquals("remote", AiConfig.DEFAULT_MODE);
    }

    @Test
    public void testInitParamConstants() {
        assertEquals("org.atmosphere.ai.llmMode", AiConfig.LLM_MODE);
        assertEquals("org.atmosphere.ai.llmModel", AiConfig.LLM_MODEL);
        assertEquals("org.atmosphere.ai.llmApiKey", AiConfig.LLM_API_KEY);
        assertEquals("org.atmosphere.ai.llmBaseUrl", AiConfig.LLM_BASE_URL);
    }
}
