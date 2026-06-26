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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical RAG chat endpoint and the page the bundled Atmosphere Console
 * connects to (it sits at the default {@code /atmosphere/ai-chat} path). Every
 * turn, Atmosphere automatically retrieves context through the declared
 * {@link KnowledgeBaseContextProvider} and injects it into the prompt.
 *
 * <p>Because the provider is declared here, the framework wraps it with the
 * default-on injection-safety screen ({@code atmosphere.ai.rag.safety.*}): any
 * retrieved document that looks like an indirect prompt injection (OWASP
 * Agentic A04) is dropped before it reaches the model. Ask "how do I secure
 * Atmosphere?" and the server log shows the simulated poisoned document being
 * dropped. Disable the screen with
 * {@code atmosphere.ai.rag.safety.enabled=false} to see the difference.</p>
 *
 * <p>The richer {@link RagAgent} (slash commands + {@code @AiTool} search) stays
 * available at {@code /atmosphere/agent/rag-assistant}.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat",
        contextProviders = {KnowledgeBaseContextProvider.class},
        systemPrompt = "You are a knowledge base assistant for the Atmosphere Framework. "
                + "Answer the user's question using only the retrieved context. If the context "
                + "does not contain the answer, say so plainly.")
@AgentScope(
        purpose = "Answer questions about the Atmosphere Framework — its transports, "
                + "AI module, agents, and getting started — using the documentation knowledge base.",
        forbiddenTopics = {"medical", "legal", "financial advice"},
        tier = AgentScope.Tier.RULE_BASED)
public class RagChatEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(RagChatEndpoint.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("RAG chat prompt: {}", message);
        // Streaming through the pipeline runs retrieval (screened by the
        // injection-safety decorator) before the model is called.
        session.stream(message);
    }
}
