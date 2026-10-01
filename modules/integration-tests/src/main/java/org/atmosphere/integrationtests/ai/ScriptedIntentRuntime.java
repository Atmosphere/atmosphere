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
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.decision.RuntimeDecisionModel;
import org.atmosphere.ai.intent.IntentRoute;
import org.atmosphere.ai.intent.IntentRouting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The classifier behind the intent-routing e2e: answers one decision question
 * the way the Built-in runtime does when the endpoint returns
 * {@code top_logprobs} — the JSON answer, then the decision distribution over
 * the route codes A (track), B (general), C (agent). Its script, by keyword in
 * the message:
 * <ul>
 *   <li>{@code garbled} — a reply that is not JSON (UNPARSEABLE → ESCALATE);</li>
 *   <li>{@code joke} — {@code general} at P=0.97 (ACT, the LLM answers);</li>
 *   <li>{@code parcel} — {@code track} at P=0.8 (margin 0.7 → CONFIRM);</li>
 *   <li>{@code vague} — {@code track} at P=0.5 (margin 0.25 → ESCALATE);</li>
 *   <li>{@code order} — {@code track} at P=0.97 (margin 0.955 → ACT);</li>
 *   <li>anything else (e.g. "I need a person") — {@code agent} at P=0.8: the
 *       human route chosen at the CONFIRM tier, which escalates without asking
 *       for a confirmation.</li>
 * </ul>
 */
final class ScriptedIntentRuntime implements AgentRuntime {

    /**
     * The routing both e2e paths share: {@code track} (a deterministic
     * handler), {@code general} (the LLM path), {@code agent} (the human route),
     * classified by the real {@link RuntimeDecisionModel} over this runtime.
     *
     * @param tickets numbers the human route's hand-offs
     */
    static IntentRouting customerRouting(AtomicInteger tickets) {
        return IntentRouting.of("Which team should handle this customer message?",
                        IntentRoute.handler("track", "where an order or parcel is",
                                decision -> "Order 7 is out for delivery"),
                        IntentRoute.llm("general", "anything else"),
                        IntentRoute.human("agent", "the customer needs a person",
                                decision -> "A person will follow up (ticket T-"
                                        + tickets.incrementAndGet() + ")"))
                .withDecisionModel(new RuntimeDecisionModel(new ScriptedIntentRuntime()));
    }

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
            p = 0.8;
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
