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
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;

/**
 * Test handler for conversation memory E2E tests.
 * Uses an {@link AiStreamingSession} with memory enabled and a fake
 * {@link AiSupport} that echoes back the conversation history count
 * so the Playwright spec can verify multi-turn memory is working.
 *
 * <p>Response format: {@code HISTORY:<count>|<prompt>} where count is the
 * number of history messages at the time of the request.</p>
 */
public class ConversationMemoryTestHandler implements AtmosphereHandler {

    private final InMemoryConversationMemory memory;
    private final AgentRuntime echoingRuntime = new HistoryEchoingRuntime();

    public ConversationMemoryTestHandler(int maxMessages) {
        this.memory = new InMemoryConversationMemory(maxMessages);
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("memory-test").start(() -> {
                try (var session = new AiStreamingSession(StreamingSessions.start(resource), echoingRuntime,
                        "You are a test assistant", null, List.of(), resource, memory)) {
                    session.stream(trimmed);
                }
            });
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            return;
        }
        if (event.isClosedByClient() || event.isClosedByApplication()) {
            // Clear memory on disconnect to prevent leaks
            memory.clear(event.getResource().uuid());
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw) {
            var inner = raw.message();
            if (inner instanceof String json) {
                event.getResource().getResponse().write(json);
                event.getResource().getResponse().flushBuffer();
            }
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * AiSupport that echoes back the conversation history count and content.
     * This allows the Playwright spec to verify that history is being passed.
     */
    private static class HistoryEchoingRuntime implements AgentRuntime {

        @Override
        public String name() {
            return "history-echo";
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
            var history = context.history();
            int historyCount = history.size();

            session.sendMetadata("historyCount", historyCount);

            // Echo all history messages as metadata
            for (int i = 0; i < history.size(); i++) {
                var msg = history.get(i);
                session.sendMetadata("history_" + i + "_role", msg.role());
                session.sendMetadata("history_" + i + "_content", msg.content());
            }

            // Send response streaming texts that include the history count and prompt
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            session.send("HISTORY:");
            session.send(String.valueOf(historyCount));
            session.send("|");
            session.send(context.message());
            session.complete();
        }
    }
}
