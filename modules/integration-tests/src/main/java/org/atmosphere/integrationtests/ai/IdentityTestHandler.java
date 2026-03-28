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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;

/**
 * Test handler for AiRequest identity fields E2E tests.
 * Creates an AiRequest with identity fields populated and uses a fake
 * AiSupport that echoes them back as metadata for Playwright verification.
 */
public class IdentityTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("identity-test").start(() -> {
                var session = StreamingSessions.start(resource);
                var context = new AgentExecutionContext(
                        trimmed, "", null, "research-agent", "sess-abc",
                        "user-42", "conv-xyz",
                        java.util.List.of(), null, null, java.util.List.of(),
                        java.util.Map.of(), java.util.List.of(), null);

                new IdentityEchoingRuntime().execute(context, session);
            });
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
    }

    private static class IdentityEchoingRuntime implements AgentRuntime {

        @Override
        public String name() {
            return "identity-echo";
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
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.sendMetadata("userId", context.userId());
            session.sendMetadata("sessionId", context.sessionId());
            session.sendMetadata("agentId", context.agentId());
            session.sendMetadata("conversationId", context.conversationId());
            session.send("Identity: " + context.userId()
                    + "/" + context.sessionId()
                    + "/" + context.agentId()
                    + "/" + context.conversationId());
            session.complete();
        }
    }
}
