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
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.decision.RuntimeDecisionModel;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E2E handler for intent routing on both dispatch paths. A prompt is
 * {@code <mode>:<message>} with mode {@code endpoint} (the real
 * {@link AiStreamingSession} the {@code @AiEndpoint} handler dispatches through,
 * with {@code setIntentRouting} as {@code AiEndpointHandler} calls it) or
 * {@code pipeline} ({@link AiPipeline#setDefaultIntentRouting}). Both share one
 * {@link IntentRouting}:
 * <ul>
 *   <li>{@code track} — a deterministic handler: "Order 7 is out for delivery";</li>
 *   <li>{@code general} — the LLM path, here an echo runtime: "llm: &lt;message&gt;";</li>
 *   <li>{@code agent} — the human route: "A person will follow up (ticket T-n)".</li>
 * </ul>
 *
 * <p>The classifier is the real {@link RuntimeDecisionModel} over a scripted
 * runtime that answers the way the Built-in runtime does on a logprobs-capable
 * endpoint: the letter code of the chosen route plus a
 * {@code DECISION_LOGPROBS} distribution over the codes. Its script, by keyword
 * in the message:</p>
 * <ul>
 *   <li>{@code order} — {@code track} at P=0.97 (margin 0.955 → ACT);</li>
 *   <li>{@code joke} — {@code general} at P=0.97 (ACT, the LLM answers);</li>
 *   <li>{@code parcel} — {@code track} at P=0.8 (margin 0.7 → CONFIRM: the
 *       requester is asked through {@code /__approval/<id>/approve|deny});</li>
 *   <li>{@code vague} — {@code track} at P=0.5 (margin 0.25 → ESCALATE);</li>
 *   <li>{@code garbled} — a reply that is not JSON (UNPARSEABLE → ESCALATE).</li>
 * </ul>
 */
public class IntentRoutingTestHandler implements AtmosphereHandler {

    private final AtomicInteger tickets = new AtomicInteger();

    private final IntentRouting routing = IntentRouting.of(
                    "Which team should handle this customer message?",
                    IntentRoute.handler("track", "where an order or parcel is",
                            decision -> "Order 7 is out for delivery"),
                    IntentRoute.llm("general", "anything else"),
                    IntentRoute.human("agent", "the customer needs a person",
                            decision -> "A person will follow up (ticket T-" + tickets.incrementAndGet() + ")"))
            .withDecisionModel(new RuntimeDecisionModel(new ScriptedIntentRuntime()));

    private final AiPipeline pipeline = new AiPipeline(new EchoRuntime(), "You help customers.",
            null, null, null, List.of(), List.of(), AiMetrics.NOOP);

    public IntentRoutingTestHandler() {
        pipeline.setDefaultIntentRouting(routing);
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var prompt = resource.getRequest().getReader().readLine();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        var trimmed = prompt.trim();
        if (ApprovalRegistry.isApprovalMessage(trimmed)) {
            // The production resolution paths of each mode: the endpoint's
            // per-resource session registry, then the pipeline's registry.
            if (!AiStreamingSession.tryResolveApprovalForResource(resource.uuid(), trimmed)) {
                pipeline.tryResolveApproval(trimmed);
            }
            return;
        }
        var colon = trimmed.indexOf(':');
        var mode = colon < 0 ? "" : trimmed.substring(0, colon);
        var message = colon < 0 ? trimmed : trimmed.substring(colon + 1);
        Thread.ofVirtual().name("intent-routing-test").start(() -> {
            var target = StreamingSessions.start(resource);
            if ("pipeline".equals(mode)) {
                pipeline.execute(resource.uuid(), message, target);
                return;
            }
            var session = new AiStreamingSession(target, new EchoRuntime(), "You help customers.", null,
                    List.of(), resource, null, null, List.of(), List.of());
            session.setIntentRouting(routing);
            AiStreamingSession.registerActive(session);
            try {
                session.stream(message);
            } finally {
                AiStreamingSession.removeActiveSession(session);
            }
        });
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        if (event.getMessage() instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
        // Nothing owned: the runtimes are in-memory.
    }

    /** The LLM route: answers with the message, so the spec sees the runtime ran. */
    private static final class EchoRuntime implements AgentRuntime {
        @Override public String name() { return "intent-echo"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send("llm: " + context.message());
            session.complete();
        }
    }

    /**
     * Answers one decision question the way the Built-in runtime does when the
     * endpoint returns {@code top_logprobs}: the JSON answer, then the decision
     * distribution over the route codes A (track), B (general), C (agent).
     */
    private static final class ScriptedIntentRuntime implements AgentRuntime {
        @Override public String name() { return "intent-scripted"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT,
                    AiCapability.STRUCTURED_OUTPUT, AiCapability.NATIVE_STRUCTURED_OUTPUT);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            var state = context.message();
            if (state.contains("garbled")) {
                session.send("I think it is about an order, probably.");
                session.complete();
                return;
            }
            String code;
            double p;
            if (state.contains("joke")) {
                code = "B";
                p = 0.97;
            } else if (state.contains("parcel")) {
                code = "A";
                p = 0.8;
            } else if (state.contains("vague")) {
                code = "A";
                p = 0.5;
            } else if (state.contains("order")) {
                code = "A";
                p = 0.97;
            } else {
                code = "C";
                p = 0.97;
            }
            var rest = (1.0 - p) / 2.0;
            var distribution = new LinkedHashMap<String, Double>();
            for (var c : List.of("A", "B", "C")) {
                distribution.put(c, c.equals(code) ? p : rest);
            }
            session.send("{\"answer\":\"" + code + "\",\"confidence\":0.9}");
            session.confidence(AiConfidence.fromDecision(
                    new DecisionDistribution("answer", distribution, 1.0), List.of()));
            session.complete();
        }
    }
}
