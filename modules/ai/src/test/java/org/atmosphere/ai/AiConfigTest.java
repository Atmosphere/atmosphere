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

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AiConfigTest {

    @Test
    public void testResolveBaseUrlLocal() {
        assertEquals(AiConfig.resolveBaseUrl("local", null, "llama3.2"), AiConfig.OLLAMA_ENDPOINT);
    }

    @Test
    public void testResolveBaseUrlExplicitWins() {
        assertEquals(AiConfig.resolveBaseUrl("remote", "https://custom.api/v1", "any"), "https://custom.api/v1");
    }

    @Test
    public void testResolveBaseUrlGeminiDefault() {
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "gemini-2.5-flash"), AiConfig.GEMINI_ENDPOINT);
    }

    @Test
    public void testResolveBaseUrlOpenAiGpt() {
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "gpt-4o"), AiConfig.OPENAI_ENDPOINT);
    }

    @Test
    public void testResolveBaseUrlOpenAiO1() {
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "o1-mini"), AiConfig.OPENAI_ENDPOINT);
    }

    @Test
    public void testResolveBaseUrlOpenAiO3() {
        assertEquals(AiConfig.resolveBaseUrl("remote", null, "o3"), AiConfig.OPENAI_ENDPOINT);
    }

    @Test
    public void testResolveBaseUrlBlankExplicit() {
        assertEquals(AiConfig.resolveBaseUrl("remote", "", "gemini-2.5-flash"), AiConfig.GEMINI_ENDPOINT);
    }

    @Test
    public void testConfigureSetsInstance() {
        var settings = AiConfig.configure("local", "llama3.2", null, null);

        assertNotNull(settings);
        assertEquals(settings.model(), "llama3.2");
        assertEquals(settings.mode(), "local");
        assertTrue(settings.isLocal());
        assertEquals(settings.baseUrl(), AiConfig.OLLAMA_ENDPOINT);
        assertSame(AiConfig.get(), settings);
    }

    @Test
    public void testConfigureRemoteNotLocal() {
        var settings = AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);

        assertFalse(settings.isLocal());
        assertEquals(settings.baseUrl(), AiConfig.GEMINI_ENDPOINT);
    }

    @Test
    public void testDefaultConstants() {
        assertEquals(AiConfig.DEFAULT_MODEL, "gemini-2.5-flash");
        assertEquals(AiConfig.DEFAULT_MODE, "remote");
    }

    @Test
    public void testInitParamConstants() {
        assertEquals(AiConfig.LLM_MODE, "org.atmosphere.ai.llmMode");
        assertEquals(AiConfig.LLM_MODEL, "org.atmosphere.ai.llmModel");
        assertEquals(AiConfig.LLM_API_KEY, "org.atmosphere.ai.llmApiKey");
        assertEquals(AiConfig.LLM_BASE_URL, "org.atmosphere.ai.llmBaseUrl");
    }
}
