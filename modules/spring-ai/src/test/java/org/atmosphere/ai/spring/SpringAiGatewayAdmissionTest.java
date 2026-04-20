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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exec-level proof that {@link SpringAiAgentRuntime} routes every outbound
 * call through {@link AiGateway} — not just at source level (the parity
 * grep test), but at the live {@code runtime.execute(...)} entry point the
 * {@code @Prompt} dispatcher calls. A counting exporter replaces the
 * process-wide gateway; one execute call must land exactly one admission
 * entry with provider {@code "spring-ai"}, the context's {@code userId}, and
 * the context's {@code model}.
 *
 * <p>The v0.9 review flagged this gap: the existing
 * {@code RuntimeGatewayAdmissionParityTest} grepped method bodies for the
 * literal {@code admitThroughGateway(} call, which proves the source
 * contains the admission hop but not that the gateway is actually consulted
 * at runtime (a refactor could reintroduce a non-admitting dispatch path
 * behind a feature flag, say, and the grep would still pass). This test
 * drives the real reactor pipeline through a mocked {@link ChatClient} — a
 * pattern follow-ups can replicate for LangChain4j, ADK, Koog, Embabel, and
 * Semantic Kernel to close exec-level parity across all seven runtimes.</p>
 */
class SpringAiGatewayAdmissionTest {

    private CountingTraceExporter exporter;

    @BeforeEach
    void installCountingGateway() {
        exporter = new CountingTraceExporter();
        // 1M/hour limiter keeps the test from blocking on itself while still
        // routing the admit decision through the real rate limiter.
        var limiter = new PerUserRateLimiter(1_000_000, Duration.ofHours(1));
        AiGatewayHolder.install(new AiGateway(
                limiter, AiGateway.CredentialResolver.noop(), exporter));
    }

    @AfterEach
    void restoreDefaultGateway() {
        AiGatewayHolder.reset();
    }

    @Test
    void executeRecordsExactlyOneAdmissionWithRuntimeLabel() {
        var runtime = new SpringAiRuntimeContractTest.TestableSpringAiRuntime(
                mockToolAwareChatClient());
        var context = textContext();

        // The reactor pipeline may throw downstream (mocked ChatClient has no
        // real tokeniser / completion logic) — that's fine. The admission hop
        // fires BEFORE the native dispatch (SpringAiAgentRuntime.java:104), so
        // a recorded admission plus any downstream outcome is the signal.
        try {
            runtime.execute(context, new NoopSession());
        } catch (RuntimeException ignored) {
            // Downstream pipeline failure is irrelevant to this test.
        }

        assertEquals(1, exporter.entries.size(),
                "execute() must admit through the gateway exactly once — "
                + "saw " + exporter.entries.size() + " entries");
        var entry = exporter.entries.get(0);
        assertTrue(entry.accepted(),
                "the permissive test limiter must accept: reason=" + entry.reason());
        assertEquals("spring-ai", entry.provider(),
                "gateway trace must carry the runtime label for admin attribution");
        assertEquals(context.userId(), entry.userId(),
                "per-user rate-limit scope requires the context userId to surface "
                + "on the admission entry");
        assertEquals(context.model(), entry.model(),
                "model label drives per-model routing decisions downstream");
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "alice", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static ChatClient mockToolAwareChatClient() {
        var chatClient = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        var generation = new Generation(new AssistantMessage("Hello world"));
        var chatResponse = new ChatResponse(List.of(generation));
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));
        return chatClient;
    }

    /** Captures every admission decision so the test can inspect the full trace. */
    private static final class CountingTraceExporter
            implements AiGateway.GatewayTraceExporter {
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
