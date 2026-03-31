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
import org.atmosphere.ai.MemoryStrategy;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;

/**
 * Test handler for memory strategy E2E tests. Uses a large maxMessages
 * (100) so the strategy's selection logic is what determines what's sent.
 *
 * <p>Echoes back which strategy is active, the history count, and
 * total character count of the history for Playwright verification.</p>
 */
public class MemoryStrategyTestHandler implements AtmosphereHandler {

    private final InMemoryConversationMemory memory;
    private final AgentRuntime echoingRuntime;

    public MemoryStrategyTestHandler(MemoryStrategy strategy) {
        this.memory = new InMemoryConversationMemory(100);
        this.echoingRuntime = new StrategyEchoingRuntime(strategy);
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("strategy-test").start(() -> {
                try (var session = new AiStreamingSession(StreamingSessions.start(resource),
                        echoingRuntime, "You are a test assistant", null,
                        List.of(), resource, memory)) {
                    session.stream(trimmed);
                }
            });
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            if (event.isClosedByClient() || event.isClosedByApplication()) {
                memory.clear(event.getResource().uuid());
            }
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

    private static class StrategyEchoingRuntime implements AgentRuntime {
        private final MemoryStrategy strategy;

        StrategyEchoingRuntime(MemoryStrategy strategy) {
            this.strategy = strategy;
        }

        @Override
        public String name() {
            return "strategy-echo";
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
            var rawHistory = context.history();
            // Pass a realistic limit so summarization triggers when history grows
            // beyond the strategy's window (SummarizingStrategy uses recentWindowSize=4)
            var maxMessages = "summarizing".equals(strategy.name()) ? 8 : rawHistory.size();
            var selected = strategy.select(rawHistory, maxMessages);

            session.sendMetadata("strategy", strategy.name());
            session.sendMetadata("rawHistoryCount", rawHistory.size());
            session.sendMetadata("selectedHistoryCount", selected.size());

            // Calculate total chars in selected history
            int totalChars = selected.stream()
                    .mapToInt(m -> m.content() != null ? m.content().length() : 0)
                    .sum();
            session.sendMetadata("totalHistoryChars", totalChars);

            // Check if any system messages start with "[Conversation summary"
            boolean hasSummary = selected.stream()
                    .anyMatch(m -> "system".equals(m.role())
                            && m.content() != null
                            && m.content().startsWith("[Conversation summary"));
            session.sendMetadata("hasSummary", hasSummary);

            session.send("STRATEGY:" + strategy.name()
                    + "|RAW:" + rawHistory.size()
                    + "|SELECTED:" + selected.size()
                    + "|" + context.message());
            session.complete();
        }
    }
}
