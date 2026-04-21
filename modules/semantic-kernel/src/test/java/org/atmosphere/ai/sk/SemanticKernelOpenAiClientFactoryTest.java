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
package org.atmosphere.ai.sk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticKernelOpenAiClientFactoryTest {

    @Test
    void isOpenAiNativeRecognizesOpenAiUrls() {
        assertTrue(SemanticKernelOpenAiClientFactory.isOpenAiNative("https://api.openai.com/v1"));
        assertTrue(SemanticKernelOpenAiClientFactory.isOpenAiNative("https://api.openai.com/v1/"));
        assertTrue(SemanticKernelOpenAiClientFactory.isOpenAiNative("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    void isOpenAiNativeRejectsEverythingElse() {
        assertFalse(SemanticKernelOpenAiClientFactory.isOpenAiNative(null));
        assertFalse(SemanticKernelOpenAiClientFactory.isOpenAiNative(""));
        assertFalse(SemanticKernelOpenAiClientFactory.isOpenAiNative(
                "https://generativelanguage.googleapis.com/v1beta/openai"));
        assertFalse(SemanticKernelOpenAiClientFactory.isOpenAiNative("http://localhost:11434/v1"));
        assertFalse(SemanticKernelOpenAiClientFactory.isOpenAiNative("https://api.together.xyz/v1"));
    }

    @Test
    void rewriteUrlReplacesOpenAiSentinelWithGeminiBase() {
        // Regression: SK 1.4.0's wrapped Azure SDK appends /chat/completions
        // to whatever endpoint it was given; against Gemini's OpenAI-compat
        // surface the correct URL is {baseUrl}/chat/completions, not
        // {baseUrl}/openai/deployments/{model}/chat/completions (the Azure
        // pattern the SDK picks unless the endpoint is api.openai.com/v1).
        String source = "https://api.openai.com/v1/chat/completions";
        String rewritten = SemanticKernelOpenAiClientFactory.rewriteUrl(
                source, "https://generativelanguage.googleapis.com/v1beta/openai");

        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                rewritten);
    }

    @Test
    void rewriteUrlNormalizesTrailingSlashInRealBase() {
        // SkConfig accepts a user-supplied base URL; trailing slashes are
        // common (Gemini's docs show one). The rewrite must not produce
        // {base}//chat/completions.
        String rewritten = SemanticKernelOpenAiClientFactory.rewriteUrl(
                "https://api.openai.com/v1/chat/completions",
                "https://generativelanguage.googleapis.com/v1beta/openai/");

        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                rewritten);
    }

    @Test
    void rewriteUrlPreservesPathSuffixesOtherThanChatCompletions() {
        String rewritten = SemanticKernelOpenAiClientFactory.rewriteUrl(
                "https://api.openai.com/v1/embeddings",
                "http://localhost:11434/v1");

        assertEquals("http://localhost:11434/v1/embeddings", rewritten);
    }

    @Test
    void rewriteUrlPassesThroughUnrelatedUrls() {
        // Anything that does not start with the sentinel is untouched —
        // defensive because pipeline policies run for every request in the
        // same pipeline, including redirects.
        assertEquals("https://example.com/foo",
                SemanticKernelOpenAiClientFactory.rewriteUrl(
                        "https://example.com/foo",
                        "https://generativelanguage.googleapis.com/v1beta/openai"));
        assertEquals(null,
                SemanticKernelOpenAiClientFactory.rewriteUrl(null,
                        "https://generativelanguage.googleapis.com/v1beta/openai"));
    }

    @Test
    void forEndpointReturnsClientForOpenAiEndpoint() {
        // The factory must not throw for the happy path — when the endpoint
        // is OpenAI itself, no rewrite policy is installed.
        var client = SemanticKernelOpenAiClientFactory.forEndpoint(
                "https://api.openai.com/v1", "sk-test");
        assertNotNull(client);
    }

    @Test
    void forEndpointReturnsClientForNullEndpoint() {
        // Null endpoint falls through to OpenAI's default; don't NPE.
        var client = SemanticKernelOpenAiClientFactory.forEndpoint(null, "sk-test");
        assertNotNull(client);
    }

    @Test
    void forEndpointReturnsClientForGeminiEndpoint() {
        // The Gemini path installs the rewrite policy; builder must not throw.
        var client = SemanticKernelOpenAiClientFactory.forEndpoint(
                "https://generativelanguage.googleapis.com/v1beta/openai", "AIza-test");
        assertNotNull(client);
    }
}
