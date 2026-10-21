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
package org.atmosphere.ai;

import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call the {@link AiGateway} refuses (per-user rate limit) ends its
 * {@link StreamingSession} with one error the client sees, typed as
 * {@link AiProviderException.RateLimited}, on every dispatch mode of the shared
 * {@link AbstractAgentRuntime} path: before the fix the rejection was thrown
 * with the session still open, so a caller that did not error it itself (a
 * direct {@code runtime.execute(ctx, session)}, {@code AiPipeline.execute}) or
 * that trusted a failed {@code whenDone()} to mean "the runtime already
 * reported it" ({@code AiStreamingSession}) left the client waiting forever.
 */
class GatewayRejectionEndsSessionTest {

    private static final String USER = "alice";

    @BeforeEach
    void installExhaustedGateway() {
        var gateway = new AiGateway(new PerUserRateLimiter(1, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(), entry -> { });
        // Spend the only admission of the user, and of the anonymous bucket an
        // AiPipeline call (no user id) is admitted against, so the next call is
        // refused.
        assertTrue(gateway.admit(USER, "drain", "default").accepted());
        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "drain", "default").accepted());
        AiGatewayHolder.install(gateway);
    }

    @AfterEach
    void restoreDefaultGateway() {
        AiGatewayHolder.reset();
    }

    @Test
    void executeEndsSessionWhenGatewayRejects() {
        var runtime = new AdmittingRuntime();
        var session = new CountingSession();

        var thrown = assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.execute(context(), session));

        assertFalse(runtime.modelCalled, "a refused call must not reach the model");
        assertEndedOnceWith(session, thrown);
    }

    @Test
    void executeWithHandleEndsSessionWhenGatewayRejectsOnCallingThread() {
        var runtime = new AdmittingRuntime();
        var session = new CountingSession();

        var thrown = assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.executeWithHandle(context(), session));

        assertEndedOnceWith(session, thrown);
    }

    @Test
    void executeWithHandleEndsSessionWhenGatewayRejectsOnWorkerThread() throws Exception {
        var runtime = new WorkerAdmittingRuntime();
        var session = new CountingSession();

        var handle = runtime.executeWithHandle(context(), session);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handle.whenDone().get(5, TimeUnit.SECONDS));

        assertInstanceOf(AiProviderException.RateLimited.class, failure.getCause());
        assertEndedOnceWith(session, failure.getCause());
    }

    @Test
    void executeEndsSessionWhenClientIsNotConfigured() {
        var runtime = new UnconfiguredRuntime();
        var session = new CountingSession();

        var thrown = assertThrows(IllegalStateException.class,
                () -> runtime.execute(context(), session));

        assertEndedOnceWith(session, thrown);
    }

    @Test
    void executeWithHandleEndsSessionWhenClientIsNotConfigured() {
        var runtime = new UnconfiguredRuntime();
        var session = new CountingSession();

        var thrown = assertThrows(IllegalStateException.class,
                () -> runtime.executeWithHandle(context(), session));

        assertEndedOnceWith(session, thrown);
    }

    @Test
    void builtInExecuteEndsSessionWhenGatewayRejects() {
        var client = new RecordingClient();
        var runtime = new BuiltInAgentRuntime();
        runtime.configureNativeClient(client);
        var session = new CountingSession();

        var thrown = assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.execute(context(), session));

        assertEquals(0, client.calls.get(), "a refused call must not reach the model");
        assertEndedOnceWith(session, thrown);
    }

    @Test
    void builtInExecuteWithHandleEndsSessionWhenGatewayRejects() {
        var client = new RecordingClient();
        var runtime = new BuiltInAgentRuntime();
        runtime.configureNativeClient(client);
        var session = new CountingSession();

        var thrown = assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.executeWithHandle(context(), session));

        assertEquals(0, client.calls.get(), "a refused call must not reach the model");
        assertEndedOnceWith(session, thrown);
    }

    @Test
    void builtInGenerateReportsTheRejectionOnce() {
        var client = new RecordingClient();
        var runtime = new BuiltInAgentRuntime();
        runtime.configureNativeClient(client);

        assertThrows(AiProviderException.RateLimited.class,
                () -> runtime.generate(context(), Duration.ofSeconds(5)));
        assertEquals(0, client.calls.get(), "a refused call must not reach the model");
    }

    @Test
    void pipelineEndsSessionOnceWhenGatewayRejects() {
        var runtime = new AdmittingRuntime();
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                null, null, List.of(), List.of(), null);
        var session = new CountingSession();

        try {
            pipeline.execute(USER, "hello", session);
        } catch (AiProviderException.RateLimited expected) {
            // Whether the refusal also reaches the caller is not what this pins.
        }

        assertTrue(session.isClosed(), "the refused call must end the session");
        assertEquals(1, session.errors.get(), "the session must be ended exactly once");
        assertInstanceOf(AiProviderException.RateLimited.class, session.failure(),
                "the client must see the rate-limit refusal");
    }

    @Test
    void pipelineEndsSessionWhenRequestAssemblyThrows() {
        var runtime = new AdmittingRuntime();
        var failure = new IllegalStateException("memory store unavailable");
        var memory = new AiConversationMemory() {
            @Override
            public List<org.atmosphere.ai.llm.ChatMessage> getHistory(String conversationId) {
                throw failure;
            }

            @Override
            public void addMessage(String conversationId, org.atmosphere.ai.llm.ChatMessage message) {
            }

            @Override
            public void clear(String conversationId) {
            }

            @Override
            public int maxMessages() {
                return 10;
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "gpt-4",
                memory, null, List.of(), List.of(), null);
        var session = new CountingSession();

        var thrown = assertThrows(IllegalStateException.class,
                () -> pipeline.execute(USER, "hello", session));

        assertEquals(failure, thrown);
        assertFalse(runtime.modelCalled, "no model call starts for a turn that failed to assemble");
        assertEndedOnceWith(session, thrown);
    }

    private static void assertEndedOnceWith(CountingSession session, Throwable expected) {
        assertTrue(session.isClosed(), "the refused call must end the session");
        assertEquals(1, session.errors.get(), "the session must be ended exactly once");
        assertEquals(expected, session.failure(),
                "the client must see the failure the caller got");
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "hello", "system prompt", "gpt-4",
                null, "session-1", USER, "conv-1",
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);
    }

    /** Admits on the calling thread, as most runtimes do from {@code doExecute}. */
    static class AdmittingRuntime extends AbstractAgentRuntime<String> {
        volatile boolean modelCalled;

        @Override
        public String name() {
            return "admitting";
        }

        @Override
        protected String nativeClientClassName() {
            return "java.lang.String";
        }

        @Override
        protected String createNativeClient(AiConfig.LlmSettings settings) {
            return "client";
        }

        @Override
        protected void doExecute(String client, AgentExecutionContext context,
                                 StreamingSession session) {
            admitThroughGateway(context);
            modelCalled = true;
            session.complete();
        }

        @Override
        protected String clientDescription() {
            return "client";
        }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
    }

    /**
     * Admits on a worker and fails the handle's future, as Spring AI Alibaba
     * does: its {@code doExecuteWithHandle} runs {@code doExecute} on a virtual
     * thread.
     */
    static class WorkerAdmittingRuntime extends AdmittingRuntime {
        @Override
        protected ExecutionHandle doExecuteWithHandle(String client, AgentExecutionContext context,
                                                      StreamingSession session) {
            var done = new CompletableFuture<Void>();
            Thread.startVirtualThread(() -> {
                try {
                    doExecute(client, context, session);
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
            return new ExecutionHandle() {
                @Override public void cancel() { }

                @Override public boolean isDone() { return done.isDone(); }

                @Override public CompletableFuture<Void> whenDone() { return done; }
            };
        }
    }

    /** Builds no client, so the dispatch fails before any model call. */
    static class UnconfiguredRuntime extends AdmittingRuntime {
        @Override
        protected String createNativeClient(AiConfig.LlmSettings settings) {
            return null;
        }
    }

    private static final class RecordingClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            calls.incrementAndGet();
            session.complete();
        }

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session,
                                         java.util.concurrent.atomic.AtomicBoolean cancelled,
                                         java.util.function.Consumer<java.io.Closeable> streamSink) {
            calls.incrementAndGet();
            session.complete();
        }
    }

    /** Counts every {@code error} call, the ones after the session ended included. */
    private static final class CountingSession implements StreamingSession {
        private final CollectingSession delegate = new CollectingSession("gateway-rejection");
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
