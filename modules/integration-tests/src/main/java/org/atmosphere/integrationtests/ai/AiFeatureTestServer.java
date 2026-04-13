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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.SummarizingStrategy;
import org.atmosphere.ai.TokenWindowStrategy;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Jetty server with all AI feature test endpoints registered.
 * Used by Playwright E2E tests via exec:java.
 */
public class AiFeatureTestServer {

    private static final Logger logger = LoggerFactory.getLogger(AiFeatureTestServer.class);

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("server.port", 8090);

        try (var server = new EmbeddedAtmosphereServer()
                .withPort(port)
                .withInitParam(ApplicationConfig.ANNOTATION_PACKAGE, "NONE")
                .withInitParam(ApplicationConfig.WEBSOCKET_SUPPORT, "true")) {
            server.start();

            var framework = server.getFramework();

            // Register AI test handlers
            framework.addAtmosphereHandler("/ai/filters", new FilterTestHandler());
            framework.addAtmosphereHandler("/ai/fanout", new FanOutTestHandler());
            framework.addAtmosphereHandler("/ai/cache", new CacheTestHandler());
            framework.addAtmosphereHandler("/ai/routing", new RoutingTestHandler());
            framework.addAtmosphereHandler("/ai/budget", new BudgetTestHandler());
            framework.addAtmosphereHandler("/ai/cache-coalescing", new CacheCoalescingTestHandler());
            framework.addAtmosphereHandler("/ai/cost-routing", new CostLatencyRoutingTestHandler());
            framework.addAtmosphereHandler("/ai/combined-cost-cache", new CombinedCostCacheTestHandler());
            framework.addAtmosphereHandler("/ai/classroom/math", new ClassroomTestHandler("math"));
            framework.addAtmosphereHandler("/ai/classroom/code", new ClassroomTestHandler("code"));
            framework.addAtmosphereHandler("/ai/memory", new ConversationMemoryTestHandler(20));
            framework.addAtmosphereHandler("/ai/error-recovery", new ErrorRecoveryTestHandler());
            framework.addAtmosphereHandler("/ai/events", new AiEventTestHandler());
            framework.addAtmosphereHandler("/ai/identity", new IdentityTestHandler());
            framework.addAtmosphereHandler("/ai/memory-token-window",
                    new MemoryStrategyTestHandler(new TokenWindowStrategy(200)));
            framework.addAtmosphereHandler("/ai/memory-summarizing",
                    new MemoryStrategyTestHandler(new SummarizingStrategy(4)));
            framework.addAtmosphereHandler("/ai/multimodal", new MultiModalTestHandler());
            framework.addAtmosphereHandler("/ai/cache-hint", new CacheHintTestHandler());
            framework.addAtmosphereHandler("/ai/embedding", new EmbeddingTestHandler());
            framework.addAtmosphereHandler("/ai/retry-policy", new RetryPolicyTestHandler());
            framework.addAtmosphereHandler("/ai/tool-call-delta", new ToolCallDeltaTestHandler());
            framework.addAtmosphereHandler("/ai/lifecycle-listener", new LifecycleListenerTestHandler());
            framework.addAtmosphereHandler("/ai/models", new ModelsTestHandler());
            framework.addAtmosphereHandler("/ai/hitl-real", new HitlApprovalTestHandler());
            // Wire-level ExecutionHandle.cancel() regression matrix (5 rows):
            // Built-in exercises the real runtime stream-close path; the
            // framework rows exercise the handler/session/wire contract via
            // ExecutionHandle.Settable since those runtime modules are not on
            // the integration-tests classpath. Semantic Kernel and Embabel
            // are intentionally excluded — both no-op cancel by design.
            framework.addAtmosphereHandler("/ai/cancel/built-in",
                    new CancelTestHandler("built-in"));
            framework.addAtmosphereHandler("/ai/cancel/spring-ai",
                    new CancelTestHandler("spring-ai"));
            framework.addAtmosphereHandler("/ai/cancel/langchain4j",
                    new CancelTestHandler("langchain4j"));
            framework.addAtmosphereHandler("/ai/cancel/adk",
                    new CancelTestHandler("adk"));
            framework.addAtmosphereHandler("/ai/cancel/koog",
                    new CancelTestHandler("koog"));
            // Real-LLM tier: only wired when LLM_MODE indicates a live provider.
            // Keeps the default fake-mode test matrix free of network dependencies.
            var llmMode = System.getenv().getOrDefault("LLM_MODE", "fake");
            if (llmMode.startsWith("real-")) {
                framework.addAtmosphereHandler("/ai/real/chat",
                        new org.atmosphere.integrationtests.ai.real.RealLlmChatTestHandler());
                logger.info("Real-LLM handler registered at /ai/real/chat (LLM_MODE={})", llmMode);
            }

            logger.info("AI Feature Test Server started on port {}", server.getPort());
            logger.info("Endpoints: /ai/filters, /ai/fanout, /ai/cache, /ai/routing, /ai/budget, "
                    + "/ai/cache-coalescing, /ai/cost-routing, /ai/combined-cost-cache, "
                    + "/ai/classroom/math, /ai/classroom/code, /ai/memory, /ai/error-recovery, "
                    + "/ai/events, /ai/identity, /ai/memory-token-window, /ai/memory-summarizing, "
                    + "/ai/multimodal, /ai/cache-hint, /ai/embedding");

            Thread.currentThread().join();
        }
    }
}
