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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Billing agent — receives handoffs from the support agent for
 * billing-related questions.
 */
@Agent(name = "billing",
        skillFile = "prompts/billing-skill.md",
        description = "Billing specialist — handles invoices, payments, and refunds")
public class BillingAgent {

    private static final Logger logger = LoggerFactory.getLogger(BillingAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Customer {} connected to billing", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Billing received: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            DemoResponseProducer.streamBilling(message, session);
            return;
        }
        session.stream(message);
    }

    @AiTool(name = "get_invoice",
            description = "Retrieve the latest invoice for a customer")
    public String getInvoice(
            @Param(value = "account_id", description = "The account ID")
            String accountId) {
        return "Invoice #INV-2026-0342 for account " + accountId
                + ": $99.00 (Professional Plan, March 2026). Status: Paid.";
    }

    @AiTool(name = "process_refund",
            description = "Process a refund for a customer")
    public String processRefund(
            @Param(value = "invoice_id", description = "The invoice ID to refund")
            String invoiceId,
            @Param(value = "amount", description = "Refund amount in dollars")
            String amount) {
        return "Refund of $" + amount + " processed for invoice " + invoiceId
                + ". Funds will appear in 3-5 business days.";
    }
}
