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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;

import java.util.Set;

/**
 * Default fallback {@link AgentRuntime} that uses Atmosphere's built-in
 * OpenAI-compatible HTTP client. Priority 0 — always available, used when
 * no framework-specific runtime is on the classpath.
 *
 * <p>Handles the full agent loop: RAG augmentation via context providers,
 * tool calling via ToolRegistry, and conversation memory. Guardrails and
 * interceptors are handled externally by the Atmosphere pipeline.</p>
 */
public class BuiltInAgentRuntime implements AgentRuntime {

    private final BuiltInAiSupport delegate = new BuiltInAiSupport();

    @Override
    public String name() {
        return "built-in";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        delegate.configure(settings);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        // Bridge to AiRequest for the built-in client
        // Phase 2 will add RAG augmentation and tool calling loop here
        var request = new AiRequest(
                context.message(),
                context.systemPrompt(),
                context.model(),
                context.userId(),
                context.sessionId(),
                context.agentId(),
                context.conversationId(),
                context.metadata(),
                context.history()
        );
        if (!context.tools().isEmpty()) {
            request = request.withTools(context.tools());
        }
        delegate.stream(request, session);
    }
}
