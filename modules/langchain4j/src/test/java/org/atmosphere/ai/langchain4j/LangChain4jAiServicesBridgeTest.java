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
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies the {@link LangChain4jAiServices} bridge: helper semantics
 * ({@code from} / {@code attach} / {@code of}) plus end-to-end wiring proof
 * that {@link LangChain4jAgentRuntime#doExecuteWithHandle} bypasses its
 * default {@link ChatRequest} assembly when an {@link
 * LangChain4jAiServices.Invoker} is attached, dispatches via the invoker's
 * {@link TokenStream}, and forwards lifecycle into the
 * {@link StreamingSession}.
 *
 * <p>Wiring assertions use a recording {@link RecordingSession} and a
 * {@link StubTokenStream} the test controls — same shape as
 * {@code LangChain4jGatewayAdmissionTest} but driving the bridge dispatch
 * path instead of the default ChatRequest path.</p>
 */
class LangChain4jAiServicesBridgeTest {

    @BeforeEach
    void permissiveGateway() {
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1_000_000, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                e -> { /* discard */ }));
    }

    @AfterEach
    void restoreDefault() {
        AiGatewayHolder.reset();
    }

    @Test
    void fromReturnsNullWhenNoSlot() {
        assertNull(LangChain4jAiServices.from(baseContext(Map.of())),
                "missing slot must yield null so the runtime can take its default path");
    }

    @Test
    void fromReturnsNullWhenContextIsNull() {
        assertNull(LangChain4jAiServices.from(null),
                "null context must not NPE — null is a valid 'no bridge' signal");
    }

    @Test
    void fromRejectsWrongType() {
        var ctx = baseContext(Map.of(LangChain4jAiServices.METADATA_KEY, "not an invoker"));
        var iae = assertThrows(IllegalArgumentException.class,
                () -> LangChain4jAiServices.from(ctx),
                "a non-Invoker slot must fail loudly — silently dropping the "
                        + "bridge would mask the AiService never firing");
        assertTrue(iae.getMessage().contains(LangChain4jAiServices.METADATA_KEY));
    }

    @Test
    void attachReplacesPreviousInvoker() {
        LangChain4jAiServices.Invoker first = msg -> stubStream();
        LangChain4jAiServices.Invoker second = msg -> stubStream();

        var ctx = LangChain4jAiServices.attach(baseContext(Map.of()), first);
        var ctx2 = LangChain4jAiServices.attach(ctx, second);

        assertSame(second, LangChain4jAiServices.from(ctx2),
                "attach must replace, not append — exclusive single-invoker semantics");
    }

    @Test
    void attachRejectsNullInvoker() {
        assertThrows(IllegalArgumentException.class,
                () -> LangChain4jAiServices.attach(baseContext(Map.of()), null));
    }

    @Test
    void ofRejectsNullFunction() {
        assertThrows(IllegalArgumentException.class,
                () -> LangChain4jAiServices.of(null));
    }

    @Test
    void runtimeDispatchesViaInvokerWhenAttached() {
        var model = mock(StreamingChatModel.class);
        var stub = new StubTokenStream();
        var invokedWith = new ArrayList<String>();
        LangChain4jAiServices.Invoker invoker = msg -> {
            invokedWith.add(msg);
            return stub;
        };

        var ctx = LangChain4jAiServices.attach(baseContext(Map.of()), invoker);
        var session = new RecordingSession();
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);

        var handle = runtime.executeWithHandle(ctx, session);
        // Drive the stub's lifecycle as the LC4j proxy would
        stub.partial.accept("Hello ");
        stub.partial.accept("world");
        stub.complete.accept(ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello world")).build());

        assertEquals(List.of("Hello"), invokedWith,
                "the invoker must receive the user's raw message — the bridge does "
                        + "NOT do its own assembleMessages step (the user's @SystemMessage "
                        + "owns that)");
        assertEquals(List.of("Hello ", "world"), session.tokens,
                "every onPartialResponse callback must reach session.send in order");
        assertTrue(session.completed, "onCompleteResponse must trigger session.complete");
        assertTrue(handle.isDone(), "the handle must resolve once complete fires");
        verify(model, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void runtimeForwardsInvokerErrorToSession() {
        var model = mock(StreamingChatModel.class);
        var stub = new StubTokenStream();
        LangChain4jAiServices.Invoker invoker = msg -> stub;

        var ctx = LangChain4jAiServices.attach(baseContext(Map.of()), invoker);
        var session = new RecordingSession();
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);
        runtime.executeWithHandle(ctx, session);

        var boom = new RuntimeException("upstream failed");
        stub.error.accept(boom);

        assertSame(boom, session.errors.get(0),
                "onError must reach session.error so the caller gets the failure");
        assertFalse(session.completed,
                "session.complete must NOT fire when the stream errored");
    }

    @Test
    void runtimeHandlesSynchronousInvokerThrow() {
        var model = mock(StreamingChatModel.class);
        var boom = new RuntimeException("invoker exploded");
        LangChain4jAiServices.Invoker invoker = msg -> {
            throw boom;
        };

        var ctx = LangChain4jAiServices.attach(baseContext(Map.of()), invoker);
        var session = new RecordingSession();
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);

        var handle = runtime.executeWithHandle(ctx, session);

        assertSame(boom, session.errors.get(0),
                "synchronous invoker throws must surface via session.error so the "
                        + "user's @SystemMessage rendering failures are visible");
        assertTrue(handle.isDone(), "the handle must resolve immediately on synchronous failure");
    }

    @Test
    void runtimeFallsBackToDefaultPathWhenNoBridge() {
        var model = mock(StreamingChatModel.class);
        var session = new RecordingSession();
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);

        // No bridge attached: doExecuteWithHandle must reach model.chat(...)
        org.mockito.Mockito.doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        runtime.executeWithHandle(baseContext(Map.of()), session);

        verify(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }

    private static StubTokenStream stubStream() {
        return new StubTokenStream();
    }

    /**
     * Captures the bridge's lifecycle callbacks so the test can fire
     * partial / complete / error events on demand. Implements only the
     * abstract methods the {@link TokenStream} contract requires; default
     * methods on the interface are inherited unchanged.
     */
    private static final class StubTokenStream implements TokenStream {
        Consumer<String> partial = s -> { };
        Consumer<ChatResponse> complete = r -> { };
        Consumer<Throwable> error = t -> { };

        @Override
        public TokenStream onPartialResponse(Consumer<String> consumer) {
            this.partial = consumer;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
            this.complete = consumer;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> consumer) {
            this.error = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            this.error = t -> { };
            return this;
        }

        @Override
        public void start() {
            // No-op: the test fires lifecycle callbacks directly via the
            // captured consumers above.
        }
    }

    /**
     * Captures session output so assertions can check what the bridge
     * forwarded. Implements only the methods the bridge calls.
     */
    private static final class RecordingSession implements StreamingSession {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile boolean closed;

        @Override public String sessionId() { return "aiservices-test"; }
        @Override public void send(String text) { tokens.add(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void usage(org.atmosphere.ai.TokenUsage usage) {
            assertNotNull(usage, "usage must never be null");
        }
        @Override public void complete() { completed = true; closed = true; }
        @Override public void complete(String summary) { completed = true; closed = true; }
        @Override public void error(Throwable t) { errors.add(t); closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
