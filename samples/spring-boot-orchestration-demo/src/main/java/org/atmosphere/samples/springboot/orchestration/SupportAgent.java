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

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.annotation.RequiresApproval;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support desk agent demonstrating orchestration primitives:
 * <ul>
 *   <li>{@code session.handoff("billing", msg)} — agent handoff</li>
 *   <li>{@code @RequiresApproval} — human approval gate on dangerous tools</li>
 *   <li>{@code @Command} — slash commands</li>
 *   <li>{@code @AiTool} — AI-callable tools</li>
 * </ul>
 */
@Agent(name = "support",
        skillFile = "skill:support-agent",
        description = "Support desk agent — handles general queries, hands off billing questions")
public class SupportAgent {

    private static final Logger logger = LoggerFactory.getLogger(SupportAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Customer {} connected to support", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Support received: {}", message);

        // Detect billing-related questions and hand off
        var lower = message.toLowerCase();
        if (lower.contains("bill") || lower.contains("invoice") || lower.contains("payment")
                || lower.contains("charge") || lower.contains("refund")) {
            session.handoff("billing", message);
            return;
        }

        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            DemoResponseProducer.streamSupport(message, session);
            return;
        }
        session.stream(message);
    }

    @Command(value = "/status", description = "Check your account status")
    public String status() {
        return "Account status: Active. Plan: Professional. Next billing: April 15, 2026.";
    }

    @Command(value = "/hours", description = "Support operating hours")
    public String hours() {
        return "Support hours: Mon-Fri 9am-6pm EST. Emergency support: 24/7 via /escalate.";
    }

    @Command(value = "/purge", description = "Purge all cached data",
             confirm = "This will delete all cached support data. Continue?")
    public String purge() {
        return "All cached support data has been purged.";
    }

    @AiTool(name = "lookup_account",
            description = "Look up a customer account by email or ID")
    public String lookupAccount(
            @Param(value = "identifier", description = "Email address or account ID")
            String identifier) {
        return "Account found: " + identifier + " — Plan: Professional, Status: Active, "
                + "Balance: $0.00, Member since: 2024-01-15";
    }

    @AiTool(name = "cancel_account",
            description = "Cancel a customer account permanently")
    @RequiresApproval("This will permanently cancel the account and delete all data. Are you sure?")
    public String cancelAccount(
            @Param(value = "account_id", description = "The account ID to cancel")
            String accountId) {
        return "Account " + accountId + " has been cancelled. All data scheduled for deletion in 30 days.";
    }
}
