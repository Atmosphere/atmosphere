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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiProviderException;
import org.atmosphere.ai.CollectingSession;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Spring AI Alibaba admits through the {@link AiGateway} on the worker thread
 * its {@code executeWithHandle} starts, and a refusal there fails the handle's
 * future without touching the session. Callers read a failed
 * {@code whenDone()} as "the runtime already reported it", so the client was
 * left waiting on a session nothing would end. Both entry points must end the
 * session once with the {@link AiProviderException.RateLimited} refusal.
 */
class SpringAiAlibabaGatewayRejectionTest {

    @BeforeEach
    void installExhaustedGateway() {
        var gateway = new AiGateway(new PerUserRateLimiter(1, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(), entry -> { });
        assertTrue(gateway.admit("alice", "drain", "default").accepted());
        AiGatewayHolder.install(gateway);
    }

    @AfterEach
    void restoreDefaultGateway() {
        AiGatewayHolder.reset();
    }

    @Test
    void executeWithHandleEndsSessionWhenGatewayRejects() throws Exception {
        var agent = mock(ReactAgent.class);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.configureNativeClient(agent);
        var session = new CountingSession();

        var handle = runtime.executeWithHandle(textContext(), session);
        var failure = assertThrows(ExecutionException.class,
                () -> handle.whenDone().get(5, TimeUnit.SECONDS));

        assertInstanceOf(AiProviderException.RateLimited.class, failure.getCause());
        assertTrue(session.isClosed(), "the refused call must end the session");
        assertEquals(1, session.errors.get(), "the session must be ended exactly once");
        assertEquals(failure.getCause(), session.failure());
        verifyNoInteractions(agent);
    }

    @Test
    void executeEndsSessionWhenGatewayRejects() {
        var agent = mock(ReactAgent.class);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.configureNativeClient(agent);
        var session = new CountingSession();

        var thrown = assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.execute(textContext(), session));

        assertTrue(session.isClosed(), "the refused call must end the session");
        assertEquals(1, session.errors.get(), "the session must be ended exactly once");
        assertEquals(thrown, session.failure());
        verifyNoInteractions(agent);
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                null, "session-1", "alice", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /** Counts every {@code error} call, the ones after the session ended included. */
    private static final class CountingSession implements StreamingSession {
        private final CollectingSession delegate = new CollectingSession("alibaba-gateway");
        final AtomicInteger errors = new AtomicInteger();

        Throwable failure() {
            return delegate.failure();
        }

        @Override public String sessionId() { return delegate.sessionId(); }

        @Override public void send(String text) { delegate.send(text); }

        @Override public void sendMetadata(String key, Object value) { }

        @Override public void progress(String message) { }

        @Override public void complete() { delegate.complete(); }

        @Override public void complete(String summary) { delegate.complete(summary); }

        @Override
        public void error(Throwable t) {
            errors.incrementAndGet();
            delegate.error(t);
        }

        @Override public boolean isClosed() { return delegate.isClosed(); }

        @Override public boolean hasErrored() { return delegate.failed(); }
    }
}
