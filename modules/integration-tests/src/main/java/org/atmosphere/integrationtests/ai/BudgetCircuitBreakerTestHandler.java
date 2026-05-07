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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiBudget;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * E2E test handler exercising the per-call {@link AiBudget} circuit breaker
 * through the full {@link AiPipeline} session-decorator stack. The wire
 * receives an {@code error} frame whose message includes
 * {@code "AI budget exceeded"} when the breach trips, and a normal stream
 * when no budget is configured.
 *
 * <p>Prompt forms:</p>
 * <ul>
 *   <li>{@code total:NN} — total-token cap; runtime emits 50/30 then
 *       20/10 (cumulative 110), so any cap below 110 trips on the second
 *       usage call.</li>
 *   <li>{@code steps:N} — step cap; runtime emits 10 usage events, trips
 *       on call {@code N+1}.</li>
 *   <li>{@code wallclock:NN} — wall-clock cap in ms; runtime sleeps
 *       past the deadline before its next session call.</li>
 *   <li>{@code none} — no budget; runtime emits 1M tokens to prove the
 *       decorator is absent.</li>
 *   <li>{@code per-request:total:NN} — pipeline default unset; per-request
 *       metadata carries the cap, decorator still installs.</li>
 * </ul>
 */
public class BudgetCircuitBreakerTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("budget-circuit-breaker-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        var pipeline = new AiPipeline(new TokenEmittingRuntime(prompt),
                "system", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);

        if (prompt.startsWith("per-request:total:")) {
            var cap = Long.parseLong(prompt.substring("per-request:total:".length()));
            pipeline.execute("client-1", "go", session,
                    Map.of(AiBudget.METADATA_KEY, AiBudget.ofTokens(cap)));
            return;
        }

        var budget = parseBudget(prompt);
        if (budget != null) {
            pipeline.setDefaultBudget(budget);
        }
        pipeline.execute("client-1", "go", session);
    }

    private static AiBudget parseBudget(String prompt) {
        if (prompt.equals("none")) {
            return null;
        }
        if (prompt.startsWith("total:")) {
            return AiBudget.ofTokens(Long.parseLong(prompt.substring("total:".length())));
        }
        if (prompt.startsWith("steps:")) {
            return AiBudget.ofSteps(Integer.parseInt(prompt.substring("steps:".length())));
        }
        if (prompt.startsWith("wallclock:")) {
            var ms = Long.parseLong(prompt.substring("wallclock:".length()));
            return AiBudget.ofWallClock(Duration.ofMillis(ms));
        }
        return null;
    }

    /** Drives the session deterministically based on the prompt shape. */
    private static final class TokenEmittingRuntime implements AgentRuntime {
        private final String prompt;

        TokenEmittingRuntime(String prompt) {
            this.prompt = prompt;
        }

        @Override public String name() { return "budget-test-runtime"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.BUDGET_ENFORCEMENT);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            try {
                if (prompt.equals("none")) {
                    session.send("ok");
                    session.usage(TokenUsage.of(1_000_000L, 1_000_000L));
                    session.complete();
                    return;
                }
                if (prompt.startsWith("total:") || prompt.startsWith("per-request:total:")) {
                    session.send("first chunk");
                    session.usage(TokenUsage.of(50L, 30L));
                    if (session.hasErrored()) { return; }
                    session.send("second chunk");
                    session.usage(TokenUsage.of(20L, 10L));
                    if (!session.hasErrored()) {
                        session.send("after-trip");
                        session.complete();
                    }
                    return;
                }
                if (prompt.startsWith("steps:")) {
                    for (int i = 0; i < 10; i++) {
                        session.usage(TokenUsage.of(1L, 1L));
                        if (session.hasErrored()) { return; }
                    }
                    session.complete();
                    return;
                }
                if (prompt.startsWith("wallclock:")) {
                    session.send("before");
                    var ms = Long.parseLong(prompt.substring("wallclock:".length()));
                    Thread.sleep(ms + 80);
                    session.send("after");
                    if (!session.hasErrored()) {
                        session.complete();
                    }
                    return;
                }
                session.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.error(e);
            }
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException { /* no-op */ }

    @Override
    public void destroy() { /* no-op */ }
}
