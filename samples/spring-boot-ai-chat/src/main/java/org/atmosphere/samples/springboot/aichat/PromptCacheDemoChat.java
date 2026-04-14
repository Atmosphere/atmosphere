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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.cache.InMemoryResponseCache;
import org.atmosphere.ai.llm.CacheHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Demonstrates the {@code @AiEndpoint(promptCache = ...)} annotation end-to-end
 * through the framework's own {@link AiPipeline} + {@link InMemoryResponseCache},
 * so the observable cache-hit transition on the wire is produced by the real
 * framework cache gate rather than a sample-level shim.
 *
 * <p>The {@link CacheHint.CachePolicy#CONSERVATIVE} value on
 * {@code @AiEndpoint.promptCache()} is read by the endpoint processor and
 * threaded into {@link AgentExecutionContext#metadata()} as a
 * {@link CacheHint}. On identical subsequent requests the pipeline's
 * response cache short-circuits the runtime and emits
 * {@code ai.cache.hit=true} on the wire before replaying the cached text.
 * The first request for a given prompt emits {@code ai.cache.hit=false}
 * (cache miss — runtime ran and stored the response). The framework key
 * matches the {@code ai.tokens.input} / {@code ai.tokens.output} convention
 * so observers (specs, metrics, audit) read a single canonical metadata
 * key instead of chasing per-sample shims.</p>
 *
 * <p>Because this sample runs in demo mode with no real LLM on the
 * classpath, it instantiates its own {@link AiPipeline} with an inline
 * {@link AgentRuntime} that emits a deterministic response. A production
 * endpoint would resolve the shared runtime via
 * {@link org.atmosphere.ai.AgentRuntimeResolver#resolve()} and pass it into
 * the pipeline — the cache gate fires identically either way. The pipeline
 * is cached in a field so the {@link InMemoryResponseCache} state is
 * preserved across requests on this endpoint.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat-with-cache",
        promptCache = CacheHint.CachePolicy.CONSERVATIVE)
public class PromptCacheDemoChat {

    private static final Logger logger = LoggerFactory.getLogger(PromptCacheDemoChat.class);

    /**
     * Shared framework pipeline with a pinned response cache. Created lazily
     * on the first request so the sample bean is trivially constructable for
     * Spring. The pipeline's {@code setDefaultCachePolicy} flag means every
     * {@link AiPipeline#execute(String, String, StreamingSession)} call
     * seeds {@link CacheHint#CONSERVATIVE} into the request metadata and the
     * cache gate actually fires (this path was dead code before the Bug #2
     * fix in 4.0.37 — {@code AiPipeline.execute} used to hardcode
     * {@code Map.of()} for the request metadata).
     */
    private volatile AiPipeline pipeline;

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        var policy = getClass().getAnnotation(AiEndpoint.class).promptCache();
        session.sendMetadata("prompt.cache.policy", policy.name());

        logger.info("Routing prompt through pipeline response cache: {}", message);
        pipeline().execute("prompt-cache-demo", message, session);
    }

    private AiPipeline pipeline() {
        var existing = pipeline;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (pipeline == null) {
                var cache = new InMemoryResponseCache(64);
                var p = new AiPipeline(new DemoAgentRuntime(), "cache demo", "demo-model",
                        null, null, java.util.List.of(), java.util.List.of(), AiMetrics.NOOP);
                p.setResponseCache(cache, Duration.ofMinutes(5));
                p.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);
                pipeline = p;
            }
            return pipeline;
        }
    }

    /**
     * Sample-local runtime that emits a deterministic word-by-word response.
     * The real framework cache wraps this runtime via
     * {@link org.atmosphere.ai.cache.CachingStreamingSession} on cache miss
     * and bypasses it entirely on cache hit — so only the first identical
     * prompt ever reaches this code path, and the second hits the cache.
     */
    private static final class DemoAgentRuntime implements AgentRuntime {
        @Override
        public String name() {
            return "prompt-cache-demo";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return -1;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            var response = "Cached response for: " + context.message();
            try {
                for (var word : response.split("(?<=\\s)")) {
                    session.send(word);
                    Thread.sleep(10);
                }
                session.complete(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.error(e);
            }
        }
    }
}
