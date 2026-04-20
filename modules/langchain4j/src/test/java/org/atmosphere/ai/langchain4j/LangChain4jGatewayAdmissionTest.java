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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Exec-level proof that {@link LangChain4jAgentRuntime} admits through
 * {@link AiGateway}. Mirrors {@code SpringAiGatewayAdmissionTest}: a
 * counting exporter replaces the holder; one {@code runtime.execute}
 * lands exactly one admission entry with provider {@code "langchain4j"}.
 * Closes the parity grep gap for LC4j and establishes the shape the
 * remaining runtimes' tests follow.
 */
class LangChain4jGatewayAdmissionTest {

    private CountingExporter exporter;

    @BeforeEach
    void installCountingGateway() {
        exporter = new CountingExporter();
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1_000_000, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                exporter));
    }

    @AfterEach
    void restoreDefault() {
        AiGatewayHolder.reset();
    }

    @Test
    void executeRecordsExactlyOneAdmissionWithRuntimeLabel() {
        var model = mock(StreamingChatModel.class);
        // Canned streaming path: emit "Hello world" as a partial then
        // a complete response — enough for the runtime's dispatch loop to
        // exit cleanly so the only admission entry captured is the one
        // doExecuteWithHandle fires before the native chat() call.
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("Hello world");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("Hello world"))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);
        var context = new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "alice", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);

        try {
            runtime.execute(context, new NoopSession());
        } catch (RuntimeException ignored) {
            // Any downstream surprise is fine; admission fires first.
        }

        assertEquals(1, exporter.entries.size(),
                "execute() must admit through the gateway exactly once — saw "
                + exporter.entries.size());
        var entry = exporter.entries.get(0);
        assertTrue(entry.accepted(), "test limiter accepts: " + entry.reason());
        assertEquals("langchain4j", entry.provider(),
                "gateway trace must carry the runtime label");
        assertEquals("alice", entry.userId());
        assertEquals("gpt-4", entry.model());
    }

    private static final class CountingExporter implements AiGateway.GatewayTraceExporter {
        final List<AiGateway.GatewayTraceEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void record(AiGateway.GatewayTraceEntry entry) {
            entries.add(entry);
        }
    }

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "admission-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
