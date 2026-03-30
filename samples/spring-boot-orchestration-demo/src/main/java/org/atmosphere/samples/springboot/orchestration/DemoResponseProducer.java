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

import org.atmosphere.ai.StreamingSession;

/**
 * Demo response producer for when no LLM API key is configured.
 */
final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    static void streamSupport(String message, StreamingSession session) {
        var response = "Thanks for reaching out to support! I can help with account questions, "
                + "technical issues, and general inquiries. For billing questions, I'll transfer "
                + "you to our billing specialist. Type /status to check your account or /hours "
                + "for our operating hours.";
        streamWords(response, session);
    }

    static void streamBilling(String message, StreamingSession session) {
        var response = "Welcome to billing support! I've been transferred your conversation "
                + "history so I have full context. I can help with invoices, payments, refunds, "
                + "and plan changes. How can I assist you today?";
        streamWords(response, session);
    }

    private static void streamWords(String text, StreamingSession session) {
        for (var word : text.split("(?<=\\s)")) {
            if (session.isClosed()) {
                return;
            }
            session.send(word);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        session.complete();
    }
}
