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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runnable chat surface. {@code session.stream(message)} dispatches through
 * Atmosphere's AI pipeline to the {@code SpringAiAgentRuntime}, which runs the
 * {@link org.springframework.ai.chat.client.ChatClient} bound in
 * {@link BoundChatClientConfig} — so the default {@link AuditingAdvisor} fires
 * on every turn.
 *
 * <p>When the user's message contains the word {@code audit}, the declared
 * {@link PerRequestAuditInterceptor} also attaches a per-request advisor for
 * that single turn. Watch both fire in the server log, or read the running
 * counts at {@code GET /api/advisors/audit-log}.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        interceptors = {PerRequestAuditInterceptor.class},
        systemPrompt = "You are a concise assistant.")
public class AdvisorChatEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(AdvisorChatEndpoint.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Advisor chat prompt: {}", message);
        session.stream(message);
    }
}
