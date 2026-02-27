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

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.budget.BudgetExceededException;
import org.atmosphere.ai.budget.TokenBudgetManager;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;

/**
 * Test handler for /ai/budget endpoint.
 * Users identified by X-Atmosphere-User-Id header.
 * Pre-configured budgets:
 * - user-1: 20 tokens, 50% threshold, fallback "cheap-model"
 * - user-2: 10 tokens, 80% threshold, fallback "cheap-model"
 * - user-3: 100 tokens, 90% threshold, fallback "cheap-model"
 * Each prompt generates 5 tokens.
 */
public class BudgetTestHandler implements AtmosphereHandler {

    private final TokenBudgetManager budgetManager = new TokenBudgetManager();

    public BudgetTestHandler() {
        budgetManager.setBudget(new TokenBudgetManager.Budget("user-1", 20, "cheap-model", 0.5));
        budgetManager.setBudget(new TokenBudgetManager.Budget("user-2", 10, "cheap-model", 0.8));
        budgetManager.setBudget(new TokenBudgetManager.Budget("user-3", 100, "cheap-model", 0.9));
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            var userId = resource.getRequest().getHeader("X-Atmosphere-User-Id");
            if (userId == null || userId.isBlank()) {
                userId = "anonymous";
            }

            var finalUserId = userId;
            Thread.ofVirtual().name("budget-handler").start(() -> {
                var session = StreamingSessions.start(resource);

                // Check budget and determine model
                String model;
                try {
                    var recommended = budgetManager.recommendedModel(finalUserId);
                    model = recommended.orElse("premium-model");
                } catch (BudgetExceededException e) {
                    session.sendMetadata("budget.exceeded", true);
                    session.sendMetadata("budget.remaining", 0L);
                    session.error(new RuntimeException("Budget exceeded for " + finalUserId));
                    return;
                }

                session.sendMetadata("budget.model", model);
                session.sendMetadata("budget.remaining", budgetManager.remaining(finalUserId));

                // Generate 5 tokens per request with budget tracking
                var client = FakeLlmClient.withTokens(model,
                        "Token1.", " Token2.", " Token3.", " Token4.", " Token5.");
                var request = ChatCompletionRequest.of(model, trimmed);

                var trackingSession = new org.atmosphere.ai.StreamingSession() {
                    @Override public String sessionId() { return session.sessionId(); }
                    @Override public void send(String token) {
                        budgetManager.recordUsage(finalUserId, 1);
                        session.send(token);
                    }
                    @Override public void sendMetadata(String key, Object value) {
                        session.sendMetadata(key, value);
                    }
                    @Override public void progress(String msg) { session.progress(msg); }
                    @Override public void complete() {
                        session.sendMetadata("budget.remaining", budgetManager.remaining(finalUserId));
                        session.complete();
                    }
                    @Override public void complete(String summary) {
                        session.sendMetadata("budget.remaining", budgetManager.remaining(finalUserId));
                        session.complete(summary);
                    }
                    @Override public void error(Throwable t) { session.error(t); }
                    @Override public boolean isClosed() { return session.isClosed(); }
                };

                client.streamChatCompletion(request, trackingSession);
            });
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw) {
            var inner = raw.message();
            if (inner instanceof String json) {
                event.getResource().getResponse().write(json);
                event.getResource().getResponse().flushBuffer();
            }
        }
    }

    @Override
    public void destroy() {
    }
}
