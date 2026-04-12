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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real-LLM e2e handler that routes prompts through a live
 * {@link BuiltInAgentRuntime} talking to whatever OpenAI-compatible
 * endpoint is configured via {@code LLM_BASE_URL} + {@code LLM_API_KEY}.
 * Used by the Tier-1 Ollama workflow and Tier-2 paid workflow alike —
 * the only thing that changes between tiers is the environment variables.
 *
 * <p>Structural assertions only: the Playwright spec checks that the
 * response streams at least one text delta and completes within the
 * configured timeout. Content assertions are intentionally avoided so
 * the test is stable across model versions and providers.</p>
 */
public class RealLlmChatTestHandler implements AtmosphereHandler {

    private final BuiltInAgentRuntime runtime;

    public RealLlmChatTestHandler() {
        // Build a standalone runtime pointing at the configured endpoint.
        // CI env vars LLM_BASE_URL + LLM_API_KEY + LLM_MODEL drive AiConfig
        // which BuiltInAgentRuntime reads at configure() time.
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
        var req = resource.getRequest();
        if ("POST".equalsIgnoreCase(req.getMethod())) {
            var body = req.getReader().lines().reduce("", (a, b) -> a + b);
            if (body == null || body.isBlank()) {
                body = "Say hello";
            }
            var session = new BroadcasterStreamingSession(resource);
            var context = new AgentExecutionContext(
                    body, "You are a concise assistant. Reply in one short sentence.",
                    null, null, UUID.randomUUID().toString(), "user-1", "conv-1",
                    List.of(), null, null, List.of(), Map.of(),
                    List.of(), null, null, List.of(), List.of(),
                    ToolApprovalPolicy.annotated());
            try {
                runtime.execute(context, session);
            } catch (Exception e) {
                session.error(e);
            }
        } else {
            resource.suspend();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var r = event.getResource();
        var msg = event.getMessage();
        if (msg != null && r.getResponse() != null) {
            r.getResponse().write(msg.toString());
        }
    }

    @Override
    public void destroy() { }

    /** Minimal StreamingSession that broadcasts every token via the resource's broadcaster. */
    private static final class BroadcasterStreamingSession implements StreamingSession {
        private final AtmosphereResource resource;
        private volatile boolean closed;
        private volatile boolean errored;

        BroadcasterStreamingSession(AtmosphereResource resource) {
            this.resource = resource;
        }

        @Override public String sessionId() { return resource.uuid(); }

        @Override
        public void send(String text) {
            resource.getBroadcaster().broadcast(
                    "{\"type\":\"text-delta\",\"text\":" + quote(text) + "}\n");
        }

        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }

        @Override
        public void complete() {
            resource.getBroadcaster().broadcast("{\"type\":\"complete\"}\n");
            closed = true;
        }

        @Override
        public void complete(String summary) { complete(); }

        @Override
        public void error(Throwable t) {
            errored = true;
            resource.getBroadcaster().broadcast(
                    "{\"type\":\"error\",\"message\":" + quote(t.getMessage()) + "}\n");
            closed = true;
        }

        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return errored; }

        private static String quote(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }
}
