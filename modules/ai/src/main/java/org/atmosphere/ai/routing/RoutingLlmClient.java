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
package org.atmosphere.ai.routing;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.budget.BudgetExceededException;
import org.atmosphere.ai.budget.StreamingTextBudgetManager;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An {@link LlmClient} decorator that routes prompts to different backends
 * based on configurable rules.
 *
 * <p>Rules are evaluated in order. The first matching rule determines the target
 * client and model. If no rule matches, the default client is used.</p>
 *
 * <h3>Rule types</h3>
 * <ul>
 *   <li>{@link RoutingRule.ContentBased} — route based on prompt content (e.g., code questions → GPT-4)</li>
 *   <li>{@link RoutingRule.ModelBased} — route based on the requested model name</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var router = RoutingLlmClient.builder(defaultClient, "gemini-2.5-flash")
 *     .route(RoutingRule.contentBased(
 *         prompt -> prompt.contains("code"),
 *         openaiClient, "gpt-4o"))
 *     .route(RoutingRule.contentBased(
 *         prompt -> prompt.contains("translate"),
 *         claudeClient, "claude-3-haiku"))
 *     .build();
 *
 * // All requests go through the router:
 * router.streamChatCompletion(request, session);
 * }</pre>
 */
public final class RoutingLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(RoutingLlmClient.class);

    private final LlmClient defaultClient;
    private final String defaultModel;
    private final List<RoutingRule> rules;
    private final StreamingTextBudgetManager budgetManager;
    private final Function<ChatCompletionRequest, String> ownerResolver;

    private RoutingLlmClient(LlmClient defaultClient, String defaultModel, List<RoutingRule> rules,
                             StreamingTextBudgetManager budgetManager,
                             Function<ChatCompletionRequest, String> ownerResolver) {
        this.defaultClient = defaultClient;
        this.defaultModel = defaultModel;
        this.rules = List.copyOf(rules);
        this.budgetManager = budgetManager;
        this.ownerResolver = ownerResolver;
    }

    /**
     * A routing rule that determines which client and model to use.
     */
    public sealed interface RoutingRule
            permits RoutingRule.ContentBased, RoutingRule.ModelBased,
                    RoutingRule.CostBased, RoutingRule.LatencyBased {

        /**
         * A model option with cost/latency metadata for budget and latency-aware routing.
         *
         * @param client           the LLM client for this model
         * @param model            model name
         * @param costPerStreamingText     cost per streaming text (in arbitrary units)
         * @param averageLatencyMs average response latency in milliseconds
         * @param capability       capability score (higher = more capable); used for tie-breaking
         */
        record ModelOption(
                LlmClient client,
                String model,
                double costPerStreamingText,
                long averageLatencyMs,
                int capability
        ) {}

        /**
         * Route based on the content of the user's prompt.
         *
         * @param matcher predicate that tests the user message
         * @param target  the LLM client to route to
         * @param model   the model name to use
         */
        record ContentBased(Predicate<String> matcher, LlmClient target, String model) implements RoutingRule {}

        /**
         * Route based on the model name in the request.
         *
         * @param modelPattern predicate that tests the model name
         * @param target       the LLM client to route to
         */
        record ModelBased(Predicate<String> modelPattern, LlmClient target) implements RoutingRule {}

        /**
         * Route based on cost constraints. Selects the highest-capability model
         * whose total cost (costPerStreamingText * maxStreamingTexts) fits within the budget.
         *
         * @param maxCost the maximum allowed total cost
         * @param models  available model options with cost metadata
         */
        record CostBased(double maxCost, List<ModelOption> models) implements RoutingRule {}

        /**
         * Route based on latency constraints. Selects the highest-capability model
         * whose average latency is within the threshold.
         *
         * @param maxLatencyMs the maximum allowed average latency in milliseconds
         * @param models       available model options with latency metadata
         */
        record LatencyBased(long maxLatencyMs, List<ModelOption> models) implements RoutingRule {}

        /**
         * Create a content-based routing rule.
         */
        static ContentBased contentBased(Predicate<String> matcher, LlmClient target, String model) {
            return new ContentBased(matcher, target, model);
        }

        /**
         * Create a model-based routing rule.
         */
        static ModelBased modelBased(Predicate<String> modelPattern, LlmClient target) {
            return new ModelBased(modelPattern, target);
        }

        /**
         * Create a cost-based routing rule.
         */
        static CostBased costBased(double maxCost, List<ModelOption> models) {
            return new CostBased(maxCost, List.copyOf(models));
        }

        /**
         * Create a latency-based routing rule.
         */
        static LatencyBased latencyBased(long maxLatencyMs, List<ModelOption> models) {
            return new LatencyBased(maxLatencyMs, List.copyOf(models));
        }
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
        // Budget check — before rule evaluation
        if (budgetManager != null && ownerResolver != null) {
            var ownerId = ownerResolver.apply(request);
            if (ownerId != null) {
                try {
                    var recommended = budgetManager.recommendedModel(ownerId);
                    if (recommended.isPresent()) {
                        logger.debug("Budget degradation for {}: switching to {}", ownerId, recommended.get());
                        session.sendMetadata("routing.budget-degraded", true);
                        session.sendMetadata("routing.model", recommended.get());
                        var fallback = new ChatCompletionRequest(
                                recommended.get(), request.messages(),
                                request.temperature(), request.maxStreamingTexts());
                        defaultClient.streamChatCompletion(fallback, session);
                        return;
                    }
                } catch (BudgetExceededException e) {
                    session.error(e);
                    return;
                }
            }
        }

        // Extract user message for content-based routing
        var userMessage = request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((first, second) -> second)
                .map(m -> m.content())
                .orElse("");

        for (var rule : rules) {
            switch (rule) {
                case RoutingRule.ContentBased(var matcher, var target, var model) -> {
                    if (matcher.test(userMessage)) {
                        logger.debug("Routing to model {} based on content", model);
                        var routed = new ChatCompletionRequest(model, request.messages(),
                                request.temperature(), request.maxStreamingTexts());
                        session.sendMetadata("routing.model", model);
                        target.streamChatCompletion(routed, session);
                        return;
                    }
                }
                case RoutingRule.ModelBased(var modelPattern, var target) -> {
                    if (modelPattern.test(request.model())) {
                        logger.debug("Routing model {} to dedicated client", request.model());
                        session.sendMetadata("routing.model", request.model());
                        target.streamChatCompletion(request, session);
                        return;
                    }
                }
                case RoutingRule.CostBased(var maxCost, var models) -> {
                    var selected = models.stream()
                            .filter(m -> m.costPerStreamingText() * request.maxStreamingTexts() <= maxCost)
                            .max(Comparator.comparingInt(RoutingRule.ModelOption::capability));
                    if (selected.isPresent()) {
                        var opt = selected.get();
                        logger.debug("Routing to model {} based on cost (budget: {})", opt.model(), maxCost);
                        var routed = new ChatCompletionRequest(opt.model(), request.messages(),
                                request.temperature(), request.maxStreamingTexts());
                        session.sendMetadata("routing.model", opt.model());
                        session.sendMetadata("routing.cost",
                                opt.costPerStreamingText() * request.maxStreamingTexts());
                        opt.client().streamChatCompletion(routed, session);
                        return;
                    }
                }
                case RoutingRule.LatencyBased(var maxLatencyMs, var models) -> {
                    var selected = models.stream()
                            .filter(m -> m.averageLatencyMs() <= maxLatencyMs)
                            .max(Comparator.comparingInt(RoutingRule.ModelOption::capability));
                    if (selected.isPresent()) {
                        var opt = selected.get();
                        logger.debug("Routing to model {} based on latency (max: {}ms)",
                                opt.model(), maxLatencyMs);
                        var routed = new ChatCompletionRequest(opt.model(), request.messages(),
                                request.temperature(), request.maxStreamingTexts());
                        session.sendMetadata("routing.model", opt.model());
                        session.sendMetadata("routing.latency", opt.averageLatencyMs());
                        opt.client().streamChatCompletion(routed, session);
                        return;
                    }
                }
            }
        }

        // No rule matched — use default
        logger.debug("No routing rule matched, using default model {}", defaultModel);
        var defaultRequest = new ChatCompletionRequest(defaultModel, request.messages(),
                request.temperature(), request.maxStreamingTexts());
        session.sendMetadata("routing.model", defaultModel);
        defaultClient.streamChatCompletion(defaultRequest, session);
    }

    /**
     * Create a new builder.
     *
     * @param defaultClient the fallback client when no rule matches
     * @param defaultModel  the fallback model name
     * @return a new builder
     */
    public static Builder builder(LlmClient defaultClient, String defaultModel) {
        return new Builder(defaultClient, defaultModel);
    }

    public static final class Builder {
        private final LlmClient defaultClient;
        private final String defaultModel;
        private final List<RoutingRule> rules = new ArrayList<>();
        private StreamingTextBudgetManager budgetManager;
        private Function<ChatCompletionRequest, String> ownerResolver;

        private Builder(LlmClient defaultClient, String defaultModel) {
            this.defaultClient = defaultClient;
            this.defaultModel = defaultModel;
        }

        /**
         * Add a routing rule. Rules are evaluated in order.
         */
        public Builder route(RoutingRule rule) {
            rules.add(rule);
            return this;
        }

        /**
         * Enable budget-aware routing. When an owner's usage exceeds the degradation
         * threshold, the router switches to the fallback model before evaluating rules.
         *
         * @param mgr           the budget manager
         * @param ownerResolver resolves a request to an owner ID (e.g., user or org)
         * @return this builder
         */
        public Builder budgetManager(StreamingTextBudgetManager mgr,
                                     Function<ChatCompletionRequest, String> ownerResolver) {
            this.budgetManager = mgr;
            this.ownerResolver = ownerResolver;
            return this;
        }

        public RoutingLlmClient build() {
            return new RoutingLlmClient(defaultClient, defaultModel, rules,
                    budgetManager, ownerResolver);
        }
    }
}
