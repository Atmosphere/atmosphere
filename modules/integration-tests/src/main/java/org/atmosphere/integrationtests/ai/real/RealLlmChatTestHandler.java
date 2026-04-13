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
package org.atmosphere.integrationtests.ai.real;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real-LLM e2e handler that routes prompts through a live
 * {@link BuiltInAgentRuntime} talking to whatever OpenAI-compatible
 * endpoint is configured via {@code LLM_BASE_URL} + {@code LLM_API_KEY}.
 *
 * <p>Mirrors {@code MultiModalTestHandler}'s wire pattern:
 * {@code onRequest} suspends the resource and spawns a VT that grabs a
 * proper {@code StreamingSession} via {@link StreamingSessions#start},
 * then calls {@code runtime.execute(context, session)}. Frames are
 * delivered via {@link AtmosphereHandler#onStateChange} which writes
 * {@link RawMessage} payloads to the response body so the Playwright
 * {@code AiWsClient} sees them in the standard Atmosphere AI frame
 * format.</p>
 */
public class RealLlmChatTestHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(RealLlmChatTestHandler.class);

    private final BuiltInAgentRuntime runtime;

    public RealLlmChatTestHandler() {
        if (AiConfig.get() == null) {
            AiConfig.fromEnvironment();
        }
        this.runtime = new BuiltInAgentRuntime();
        var settings = AiConfig.get();
        if (settings != null) {
            this.runtime.configure(settings);
        }
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt == null || prompt.trim().isEmpty()) {
            prompt = "Say hello in one short sentence.";
        }
        var finalPrompt = prompt.trim();
        Thread.ofVirtual().name("real-llm-chat").start(() -> handlePrompt(finalPrompt, resource));
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            var context = new AgentExecutionContext(
                    prompt,
                    "You are a concise assistant. Reply in one short sentence.",
                    null,
                    null, UUID.randomUUID().toString(), "user-1", "conv-1",
                    List.of(), null, null, List.of(), Map.of(),
                    List.of(), null, null, List.of(), List.of(),
                    ToolApprovalPolicy.annotated());
            runtime.execute(context, session);
            if (!session.isClosed()) {
                session.complete();
            }
        } catch (Exception e) {
            logger.error("Real LLM execution failed", e);
            session.error(e);
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
        // no resources to release
    }
}
