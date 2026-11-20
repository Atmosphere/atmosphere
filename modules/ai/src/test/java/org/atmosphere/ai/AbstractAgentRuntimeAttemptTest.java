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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attempt of a turn in both dispatch modes: the synchronous
 * {@link AbstractAgentRuntime#execute} runs each attempt through
 * {@code doExecuteWithHandle} behind the turn's {@link StreamIdleWatchdog} and an
 * interruptible wait, so an interrupt cancels the model call, a stalled model
 * call is ended by the idle bound, and a failure before any terminal is still
 * retried.
 */
class AbstractAgentRuntimeAttemptTest {

    /** Generous: the CI runner may be loaded; the bound itself is 300ms. */
    private static final Duration SETTLE = Duration.ofSeconds(5);

    private final List<AiEnvironment> environments = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeEnvironments() {
        environments.forEach(AiEnvironment::close);
    }

    private AiEnvironment environment(String idleTimeout) {
        var environment = AiEnvironment.create(
                Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, idleTimeout)::get);
        environments.add(environment);
        return environment;
    }

    /** A runtime whose every attempt is scripted by {@link #attempt}. */
    static final class ScriptedRuntime extends AbstractAgentRuntime<String> {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final CountDownLatch engaged = new CountDownLatch(1);
        volatile Function<StreamingSession, ExecutionHandle> attempt;

        @Override public String name() { return "scripted"; }
        @Override protected String nativeClientClassName() { return "java.lang.String"; }
        @Override protected String createNativeClient(AiConfig.LlmSettings settings) { return "client"; }
        @Override protected String clientDescription() { return "client"; }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        protected void doExecute(String client, AgentExecutionContext context, StreamingSession session) {
            throw new AssertionError("the attempt goes through doExecuteWithHandle");
        }

        @Override
        protected ExecutionHandle doExecuteWithHandle(String client, AgentExecutionContext context,
                                                      StreamingSession session) {
            attempts.incrementAndGet();
            try {
                return attempt.apply(session);
            } finally {
                engaged.countDown();
            }
        }

        /** A handle that settles only when cancelled, counting its cancels. */
        ExecutionHandle stalled() {
            return new ExecutionHandle.Settable(cancels::incrementAndGet);
        }

        /** A handle that never settles, even when cancelled. */
        ExecutionHandle neverSettles() {
            var done = new CompletableFuture<Void>();
            return new ExecutionHandle() {
                @Override public void cancel() { cancels.incrementAndGet(); }
                @Override public boolean isDone() { return false; }
                @Override public CompletableFuture<Void> whenDone() { return done; }
            };
        }
    }

    /** A runtime with only the blocking {@link AbstractAgentRuntime#doExecute}. */
    static final class BlockingRuntime extends AbstractAgentRuntime<String> {
        final CountDownLatch engaged = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        @Override public String name() { return "blocking"; }
        @Override protected String nativeClientClassName() { return "java.lang.String"; }
        @Override protected String createNativeClient(AiConfig.LlmSettings settings) { return "client"; }
        @Override protected String clientDescription() { return "client"; }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        protected void doExecute(String client, AgentExecutionContext context, StreamingSession session) {
            engaged.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class RecordingSession implements StreamingSession {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        final List<String> text = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override public String sessionId() { return "s"; }
        @Override public void send(String t) { if (!closed) { text.add(t); } }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completions.incrementAndGet(); closed = true; }
        @Override public void complete(String summary) { complete(); }
        @Override public void error(Throwable t) { errors.add(t); closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return !errors.isEmpty(); }
    }

    static final class RecordingListener implements AgentLifecycleListener {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();

        @Override public void onCompletion(AgentExecutionContext context) { completions.incrementAndGet(); }
        @Override public void onError(AgentExecutionContext context, Throwable error) { errors.add(error); }
    }

    private static AgentExecutionContext context(RetryPolicy policy, AgentLifecycleListener listener) {
        return new AgentExecutionContext(
                "hello", null, "model", null, null, null, null,
                List.of(), null, null, List.of(),
                Map.of(), List.of(), null, null, List.of(listener), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated(),
                policy);
    }

    private static RetryPolicy retries(int max) {
        return new RetryPolicy(max, Duration.ofMillis(1), Duration.ofMillis(5), 1.0, Set.of());
    }

    @Test
    void anInterruptOfTheSyncCallerCancelsTheModelCall() throws Exception {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("0"));
        runtime.attempt = session -> runtime.stalled();
        var thrown = new AtomicReference<Throwable>();
        var flagRestored = new AtomicReference<Boolean>();
        var caller = Thread.ofVirtual().start(() -> {
            try {
                runtime.execute(context(null, new RecordingListener()), new RecordingSession());
            } catch (Throwable t) {
                thrown.set(t);
                flagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(runtime.engaged.await(SETTLE.toMillis(), TimeUnit.MILLISECONDS));
        caller.interrupt();
        caller.join(SETTLE);

        assertInstanceOf(CancellationException.class, thrown.get(),
                "an interrupted sync turn ends with a CancellationException");
        assertTrue(flagRestored.get(), "the interrupt flag is restored for the caller");
        assertEquals(1, runtime.cancels.get(), "the interrupt reached the attempt's handle");
    }

    @Test
    void aFailureBeforeAnyTerminalIsStillRetried() {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("0"));
        runtime.attempt = session -> switch (runtime.attempts.get()) {
            // A throw from doExecuteWithHandle, then a handle that fails
            // asynchronously, both before the session saw anything.
            case 1 -> throw new IllegalStateException("connect refused");
            case 2 -> {
                var settable = new ExecutionHandle.Settable(null);
                settable.completeExceptionally(new IllegalStateException("503"));
                yield settable;
            }
            default -> {
                session.send("hi");
                session.complete();
                yield ExecutionHandle.completed();
            }
        };
        var session = new RecordingSession();
        var listener = new RecordingListener();

        runtime.execute(context(retries(3), listener), session);

        assertEquals(3, runtime.attempts.get());
        assertEquals(List.of("hi"), session.text);
        assertEquals(1, session.completions.get());
        assertEquals(1, listener.completions.get());
        assertEquals(List.of(), listener.errors);
    }

    @Test
    void aFireEndsTheTurnAndStopsTheRetry() {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("300ms"));
        runtime.attempt = session -> {
            session.send("partial");
            return runtime.stalled();
        };
        var session = new RecordingSession();
        var listener = new RecordingListener();

        assertTimeoutPreemptively(SETTLE,
                () -> runtime.execute(context(retries(3), listener), session),
                "a stalled sync turn returns once the idle bound fires");

        assertEquals(1, runtime.attempts.get(), "a fire is the turn's end, never retried");
        assertEquals(1, runtime.cancels.get(), "the fire cancelled the model call");
        assertEquals(1, session.errors.size());
        assertInstanceOf(TimeoutException.class, session.errors.getFirst());
        assertEquals(0, session.completions.get());
        assertEquals(1, listener.errors.size(), "onError fires once: " + listener.errors);
        assertInstanceOf(TimeoutException.class, listener.errors.getFirst());
        assertEquals(0, listener.completions.get());
    }

    @Test
    void aTimeoutWhoseHandleNeverSettlesFiresOnErrorOnceInBothModes() throws Exception {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("300ms"));
        runtime.attempt = session -> runtime.neverSettles();

        var syncSession = new RecordingSession();
        var syncListener = new RecordingListener();
        assertTimeoutPreemptively(SETTLE,
                () -> runtime.execute(context(null, syncListener), syncSession));
        assertEquals(1, syncListener.errors.size(), "sync: " + syncListener.errors);
        assertEquals(0, syncListener.completions.get());
        assertEquals(1, syncSession.errors.size());

        var handleSession = new RecordingSession();
        var handleListener = new RecordingListener();
        var handle = runtime.executeWithHandle(context(null, handleListener), handleSession);
        var failure = assertThrows(ExecutionException.class,
                () -> handle.whenDone().get(SETTLE.toMillis(), TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(1, handleListener.errors.size(), "handle: " + handleListener.errors);
        assertEquals(0, handleListener.completions.get());
        assertEquals(1, handleSession.errors.size());
        assertEquals(2, runtime.cancels.get(), "each turn's fire cancelled its model call once");
    }

    @Test
    void aStalledRuntimeTimesOutInBothModesAndDropsLateOutput() throws Exception {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("300ms"));
        var tracked = new CopyOnWriteArrayList<StreamingSession>();
        runtime.attempt = session -> {
            tracked.add(session);
            session.send("one");
            return runtime.stalled();
        };

        var syncSession = new RecordingSession();
        assertTimeoutPreemptively(SETTLE,
                () -> runtime.execute(context(null, new RecordingListener()), syncSession));

        var handleSession = new RecordingSession();
        var handle = runtime.executeWithHandle(context(null, new RecordingListener()), handleSession);
        assertThrows(ExecutionException.class,
                () -> handle.whenDone().get(SETTLE.toMillis(), TimeUnit.MILLISECONDS));

        for (var session : List.of(syncSession, handleSession)) {
            assertEquals(1, session.errors.size());
            assertInstanceOf(TimeoutException.class, session.errors.getFirst());
            assertTrue(session.errors.getFirst().getMessage()
                    .contains(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY));
            assertEquals(0, session.completions.get());
        }
        // Output the model call writes after the fire is dropped.
        tracked.forEach(session -> {
            session.send("late");
            session.complete();
        });
        assertEquals(List.of("one"), syncSession.text);
        assertEquals(List.of("one"), handleSession.text);
        assertEquals(0, syncSession.completions.get() + handleSession.completions.get());
    }

    @Test
    void theBlockingDefaultAttemptIsInterruptedByTheFire() throws Exception {
        var runtime = new BlockingRuntime();
        runtime.attach(environment("300ms"));
        var session = new RecordingSession();

        assertTimeoutPreemptively(SETTLE,
                () -> runtime.execute(context(null, new RecordingListener()), session));

        assertEquals(1, session.errors.size());
        assertInstanceOf(TimeoutException.class, session.errors.getFirst());
        assertTrue(runtime.interrupted.await(SETTLE.toMillis(), TimeUnit.MILLISECONDS),
                "the worker blocked in doExecute is interrupted, not left running");
    }

    @Test
    void theBackoffBetweenAttemptsIsNotIdleTime() {
        var runtime = new ScriptedRuntime();
        runtime.attach(environment("300ms"));
        runtime.attempt = session -> {
            if (runtime.attempts.get() == 1) {
                throw new IllegalStateException("transient");
            }
            session.complete();
            return ExecutionHandle.completed();
        };
        var session = new RecordingSession();
        // One backoff of a full second: three times the bound.
        var policy = new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0, Set.of());

        runtime.execute(context(policy, new RecordingListener()), session);

        assertEquals(2, runtime.attempts.get());
        assertEquals(List.of(), session.errors, "the backoff ran inside a pause");
        assertEquals(1, session.completions.get());
    }
}
