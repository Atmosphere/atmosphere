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
package org.atmosphere.samples.springboot.agentchat;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates LLM streaming responses for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated response word-by-word through the session.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set LLM_API_KEY for real responses. Try /help for commands!");
            for (var word : words) {
                session.send(word);
                Thread.sleep(50);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm the DevOps agent running in demo mode. "
                    + "Try /help to see available commands, or ask me about service status, "
                    + "deployments, or infrastructure metrics.";
        }
        if (lower.contains("status") || lower.contains("service")) {
            return "In demo mode, I can simulate service checks. "
                    + "Try /status for a quick overview, or ask me to check a specific service. "
                    + "The check_service tool can look up: api-gateway, user-service, "
                    + "payment-service, notification-service.";
        }
        if (lower.contains("deploy")) {
            return "I can help with deployments! Use /deploy <service> <version> to deploy "
                    + "to staging. This command requires confirmation since it's a destructive action.";
        }
        return "I'm the DevOps assistant in demo mode. I can help with service monitoring, "
                + "deployments, and incident management. Try /help to see all commands, "
                + "or just ask me a question about your infrastructure.";
    }
}
