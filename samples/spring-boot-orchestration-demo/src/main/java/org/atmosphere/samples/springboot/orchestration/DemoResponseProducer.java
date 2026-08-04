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
package org.atmosphere.samples.springboot.orchestration;

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.springframework.stereotype.Component;

/**
 * Installs an agent-aware demo strategy so the bundled
 * {@link DemoAgentRuntime} streams the support-desk / billing-specialist
 * personas instead of its generic echo when the sample boots without an
 * {@code LLM_API_KEY}. Runs at startup; the framework then drives every
 * demo-mode request through the standard pipeline like any real runtime —
 * guardrails, interceptors, memory, metrics, and handoff frames all fire.
 *
 * <p>The {@link AgentExecutionContext#systemPrompt()} carries each agent's
 * {@code SKILL.md} body ({@code skill:support-agent} /
 * {@code skill:billing-agent}), so the strategy picks the persona by looking
 * for a distinguishing phrase in the lowercased system prompt. Both skill
 * files are vendored under {@code src/main/resources/META-INF/skills/} — the
 * first tier of the skill loader — so persona selection works offline and
 * deterministically instead of depending on the disk cache or a GitHub
 * fetch. If the prompt matches neither persona (e.g. a skill failed to load
 * and the system prompt is empty), the strategy deliberately falls back to
 * the support persona, the sample's entry point.</p>
 */
@Component
public class DemoResponseProducer {

    static final String SUPPORT_RESPONSE =
            "Thanks for reaching out to support! I can help with account questions, "
            + "technical issues, and general inquiries. For billing questions, I'll transfer "
            + "you to our billing specialist. Type /status to check your account or /hours "
            + "for our operating hours.";

    static final String BILLING_RESPONSE =
            "Welcome to billing support! I've been transferred your conversation "
            + "history so I have full context. I can help with invoices, payments, refunds, "
            + "and plan changes. How can I assist you today?";

    @PostConstruct
    public void installAgentAwareStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    static String generateFor(AgentExecutionContext context) {
        var system = context.systemPrompt() == null ? "" : context.systemPrompt().toLowerCase();
        // The support persona must be checked first: its SKILL.md body also
        // mentions the "billing specialist" it routes to, so a billing-first
        // check would misclassify the support agent.
        if (system.contains("support desk agent")) {
            return SUPPORT_RESPONSE;
        }
        if (system.contains("billing specialist")) {
            return BILLING_RESPONSE;
        }
        // Unknown persona — fall back to the support desk, the sample's entry point.
        return SUPPORT_RESPONSE;
    }
}
