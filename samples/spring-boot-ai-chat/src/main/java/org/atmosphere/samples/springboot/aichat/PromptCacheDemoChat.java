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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.CacheHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demonstrates the {@code @AiEndpoint(promptCache = ...)} annotation with an
 * observable, wire-level cache-hit signal that works in demo mode (no real LLM
 * required).
 *
 * <p>The {@link CacheHint.CachePolicy#CONSERVATIVE} value on
 * {@code @AiEndpoint.promptCache()} is honored by the Atmosphere AI pipeline:
 * when {@code session.stream(message)} is called with a real
 * {@code AgentRuntime}, {@code AiStreamingSession} seeds every
 * {@link org.atmosphere.ai.AgentExecutionContext} with a
 * {@link CacheHint#conservative()} entry under
 * {@link CacheHint#METADATA_KEY}, so runtimes that support provider-side
 * caching (Built-in OpenAI, Spring AI, LangChain4j) emit
 * {@code prompt_cache_key} on the wire and the pipeline-level
 * {@link org.atmosphere.ai.cache.ResponseCache} short-circuits identical
 * subsequent requests.</p>
 *
 * <p>This demo keeps its own in-memory {@code prompt -&gt; response} map so the
 * cache-hit transition is observable in CI without configuring a real
 * LLM backend. On the first request for a given prompt the handler emits
 * {@code prompt.cache.hit=false}, generates a demo response, and stores it.
 * On subsequent identical prompts it emits {@code prompt.cache.hit=true} and
 * replays the cached text as a single frame before completing. The annotation
 * attribute itself is also echoed as {@code prompt.cache.policy} so tests can
 * confirm the {@code @AiEndpoint} attribute is wired end-to-end.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat-with-cache",
        promptCache = CacheHint.CachePolicy.CONSERVATIVE)
public class PromptCacheDemoChat {

    private static final Logger logger = LoggerFactory.getLogger(PromptCacheDemoChat.class);

    /** In-memory sample-level cache keyed by the user prompt. */
    private final Map<String, String> responseCache = new ConcurrentHashMap<>();

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        var policy = getClass().getAnnotation(AiEndpoint.class).promptCache();
        session.sendMetadata("prompt.cache.policy", policy.name());

        var cached = responseCache.get(message);
        if (cached != null) {
            logger.info("Prompt cache HIT: {}", message);
            session.sendMetadata("prompt.cache.hit", true);
            session.send(cached);
            session.complete(cached);
            return;
        }

        logger.info("Prompt cache MISS: {}", message);
        session.sendMetadata("prompt.cache.hit", false);

        var response = "Cached response for: " + message;
        try {
            for (var word : response.split("(?<=\\s)")) {
                session.send(word);
                Thread.sleep(10);
            }
            responseCache.put(message, response);
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }
}
