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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream idle bound on Spring AI: a {@code ChatClient} stream that goes
 * silent ends the turn with one timeout, in both dispatch modes, and the
 * subscription is disposed; elements that carry no text still count as
 * activity. The stub stands for the provider: whether disposing reaches the
 * provider's HTTP connection is Spring AI's own (see the module README).
 */
class SpringAiIdleTimeoutTest {

    private static final Duration SETTLE = Duration.ofSeconds(5);

    private final List<AiEnvironment> environments = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        environments.forEach(AiEnvironment::close);
    }

    @Test
    void aSilentStreamEndsTheTurnOnceAndIsDisposedInBothModes() throws Exception {
        var disposals = new AtomicInteger();
        Supplier<Flux<ChatResponse>> stalled = () -> Flux.just(response("tick"))
                .concatWith(Flux.never())
                .doOnCancel(disposals::incrementAndGet);

        var handleSession = new Session();
        var handle = runtime(stalled, "300ms").executeWithHandle(context(), handleSession);
        var failure = assertThrows(ExecutionException.class,
                () -> handle.whenDone().get(SETTLE.toMillis(), TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());

        var syncSession = new Session();
        assertTimeoutPreemptively(SETTLE, () -> runtime(stalled, "300ms").execute(context(), syncSession));

        for (var session : List.of(handleSession, syncSession)) {
            assertEquals(1, session.errors.size(), "one terminal: " + session.errors);
            assertInstanceOf(TimeoutException.class, session.errors.getFirst());
            assertEquals(0, session.completions.get());
            assertEquals("tick", session.text());
        }
        assertEquals(2, disposals.get(), "each turn's fire disposed its subscription once");
    }

    @Test
    void elementsWithoutTextAreActivity() throws Exception {
        // Empty frames every 100 ms for about 1 s (three times the bound), then the answer.
        Supplier<Flux<ChatResponse>> live = () -> Flux.interval(Duration.ofMillis(100))
                .take(10)
                .map(i -> response(""))
                .concatWith(Flux.just(response("tick")));
        var session = new Session();

        runtime(live, "300ms").executeWithHandle(context(), session)
                .whenDone().get(SETTLE.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(List.of(), session.errors);
        assertEquals(1, session.completions.get());
        assertTrue(session.text().contains("tick"));
    }

    private SpringAiAgentRuntime runtime(Supplier<Flux<ChatResponse>> stream, String bound) {
        var runtime = new SpringAiRuntimeContractTest.TestableSpringAiRuntime(chatClient(stream));
        var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, bound)::get);
        environments.add(environment);
        runtime.attach(environment);
        return runtime;
    }

    private static ChatClient chatClient(Supplier<Flux<ChatResponse>> stream) {
        var chatClient = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Answers.RETURNS_SELF);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenAnswer(inv -> stream.get());
        return chatClient;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext("Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", null, List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    static final class Session implements StreamingSession {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> text = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        volatile boolean closed;

        @Override public String sessionId() { return "spring-ai-idle"; }
        @Override public void send(String t) { text.add(t); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completions.incrementAndGet(); closed = true; }
        @Override public void complete(String summary) { complete(); }
        @Override public void error(Throwable t) { errors.add(t); closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return !errors.isEmpty(); }

        String text() {
            return String.join("", text);
        }
    }
}
