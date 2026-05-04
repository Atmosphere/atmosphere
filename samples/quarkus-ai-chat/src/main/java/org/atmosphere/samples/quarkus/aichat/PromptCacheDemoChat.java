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
package org.atmosphere.samples.quarkus.aichat;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.cache.InMemoryResponseCache;
import org.atmosphere.ai.llm.CacheHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Quarkus port of {@code spring-boot-ai-chat#PromptCacheDemoChat}. Identical
 * wire-level behaviour: the {@code @AiEndpoint(promptCache = ...)} attribute
 * is read by the Atmosphere endpoint processor and threaded as a
 * {@link CacheHint} into the {@link AgentExecutionContext} metadata; the
 * pipeline's {@link InMemoryResponseCache} short-circuits the runtime on a
 * second identical request and emits {@code ai.cache.hit=true} on the wire
 * before replaying the cached text.
 *
 * <p>The handler instantiates its own {@link AiPipeline} so the demo works
 * out-of-box without a real LLM on the classpath. Production endpoints
 * should resolve the shared runtime via
 * {@link org.atmosphere.ai.AgentRuntimeResolver#resolve()} — the framework
 * cache gate fires identically either way.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat-with-cache",
        promptCache = CacheHint.CachePolicy.CONSERVATIVE)
@AgentScope(unrestricted = true,
        justification = "Prompt-cache demo — accepts arbitrary prompts to demonstrate CacheHint / ResponseCache behaviour.")
public class PromptCacheDemoChat {

    private static final Logger logger = LoggerFactory.getLogger(PromptCacheDemoChat.class);

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
