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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The stream idle cases of {@link AbstractAgentRuntimeContractTest}, each run
 * through {@link org.atmosphere.ai.AgentRuntime#executeWithHandle} and through
 * a synchronous {@link org.atmosphere.ai.AgentRuntime#execute} on a virtual
 * thread, against a {@link StallingHttpServer}. The runtime is attached to an
 * environment of its own carrying the bound, never the static API.
 */
final class StreamIdleContract {

    private static final Logger logger = LoggerFactory.getLogger(StreamIdleContract.class);

    /** The bound every case attaches, unless it tests another. */
    static final Duration BOUND = Duration.ofMillis(300);
    /** What a turn may take beyond the bound to settle. */
    static final Duration GRACE = Duration.ofSeconds(2);
    /** Three times the bound: work that is not the model's silence. */
    static final Duration LONG = BOUND.multipliedBy(3);
    /** How long a released connection may take to reach the server as closed. */
    static final Duration RELEASE = Duration.ofSeconds(3);
    /** How long a turn that should complete may take. */
    static final Duration COMPLETE = Duration.ofSeconds(15);

    enum Mode { HANDLE, SYNC }

    private final String name;
    private final Function<StallingHttpServer, StalledStreamFixture> factory;
    private final StallingHttpServer.Dialect dialect;

    StreamIdleContract(String name, Function<StallingHttpServer, StalledStreamFixture> factory,
                       StallingHttpServer.Dialect dialect) {
        this.name = name;
        this.factory = factory;
        this.dialect = dialect;
    }

    /** One turn in flight. */
    final class Turn {
        final Mode mode;
        final StalledStreamFixture fixture;
        final StallingHttpServer server;
        final IdleSession session = new IdleSession();
        final IdleListener listener = new IdleListener();
        final CountDownLatch settled = new CountDownLatch(1);
        final long startNanos = System.nanoTime();
        volatile ExecutionHandle handle;
        volatile Thread caller;
        volatile Throwable thrown;

        Turn(Mode mode, StalledStreamFixture fixture, StallingHttpServer server) {
            this.mode = mode;
            this.fixture = fixture;
            this.server = server;
        }

        boolean awaitSettled(Duration timeout) throws InterruptedException {
            return settled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }

        /** The caller's cancel: the handle's {@code cancel()}, or an interrupt of the sync caller. */
        void cancel() {
            if (mode == Mode.HANDLE) {
                handle.cancel();
            } else {
                caller.interrupt();
            }
        }

        String label() {
            return name + " [" + mode + "]";
        }
    }

    @FunctionalInterface
    interface Body {
        void run(Turn turn) throws Exception;
    }

    /**
     * Runs {@code body} once per mode, each time on a fresh server, fixture and
     * environment.
     *
     * @return {@code false} when the runtime has no fixture
     */
    boolean inBothModes(String knob, StallingHttpServer.Mode serverMode, List<ToolDefinition> tools,
                        ApprovalStrategy approval, Body body) throws Exception {
        for (var mode : Mode.values()) {
            try (var server = new StallingHttpServer(serverMode)) {
                var fixture = factory.apply(server);
                if (fixture == null) {
                    return false;
                }
                if (!tools.isEmpty() && !fixture.toolTurns()) {
                    abort(name + " fixture runs no tool turns (StalledStreamFixture.withToolTurns()):"
                            + " its tool and approval cases do not run");
                }
                var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, knob)::get);
                try {
                    fixture.runtime().attach(environment);
                    var turn = new Turn(mode, fixture, server);
                    var context = withListener(fixture.context(tools), turn.listener);
                    if (approval != null) {
                        context = context.withApprovalStrategy(approval);
                    }
                    start(turn, context);
                    try {
                        body.run(turn);
                    } finally {
                        // Never leave a turn running into the next case.
                        if (turn.settled.getCount() > 0) {
                            turn.cancel();
                            turn.awaitSettled(GRACE);
                        }
                    }
                } finally {
                    environment.close();
                }
            }
        }
        return true;
    }

    private void start(Turn turn, AgentExecutionContext context) {
        var runtime = turn.fixture.runtime();
        if (turn.mode == Mode.HANDLE) {
            try {
                turn.handle = runtime.executeWithHandle(context, turn.session);
                turn.handle.whenDone().whenComplete((ok, error) -> turn.settled.countDown());
            } catch (RuntimeException e) {
                turn.thrown = e;
                turn.settled.countDown();
            }
        } else {
            turn.caller = Thread.ofVirtual().name("stream-idle-sync-caller").start(() -> {
                try {
                    runtime.execute(context, turn.session);
                } catch (Throwable t) {
                    turn.thrown = t;
                } finally {
                    turn.settled.countDown();
                }
            });
        }
    }

    private static AgentExecutionContext withListener(AgentExecutionContext context, AgentLifecycleListener listener) {
        var listeners = new ArrayList<>(context.listeners());
        listeners.add(listener);
        return context.withListeners(List.copyOf(listeners));
    }

    // -- the cases --

    /** (a): a stall after some chunks ends the turn with exactly one timeout. */
    void stallEndsTheTurn(StallingHttpServer.Mode serverMode) throws Exception {
        inBothModes(BOUND.toMillis() + "ms", serverMode, List.of(), null, turn -> {
            assertTrue(turn.awaitSettled(BOUND.plus(GRACE).plus(connectAllowance())),
                    turn.label() + " stalled turn did not settle within the bound (" + BOUND + ") + "
                            + GRACE + ": the model call hangs the turn (Correctness Invariant #2)");
            assertTimedOutOnce(turn);
        });
    }

    /**
     * (b): a provider that never sends its headers ends the turn the same way,
     * and the pending request is let go, unless the fixture declares why not.
     */
    void stallBeforeHeadersEndsTheTurnAndReleases() throws Exception {
        inBothModes(BOUND.toMillis() + "ms", StallingHttpServer.Mode.stallBeforeHeaders(dialect), List.of(), null,
                turn -> {
                    assertTrue(turn.awaitSettled(BOUND.plus(GRACE).plus(connectAllowance())),
                            turn.label() + " a turn whose provider never answered did not settle within the bound");
                    assertTimedOutOnce(turn);
                    var residual = turn.fixture.beforeHeadersResidual();
                    if (residual != null) {
                        logger.warn("{} leaves a connection that stalls before its headers to the provider: {}",
                                turn.label(), residual);
                        abort(turn.label() + " leaves a connection that stalls before its headers to the provider: "
                                + residual);
                    }
                    assertTrue(turn.server.clientClosed(RELEASE),
                            turn.label() + " the turn ended but the request still waiting for its headers stayed open "
                                    + RELEASE + " later");
                });
    }

    /** (c): the stalled connection is let go when the turn times out. */
    void stallReleasesTheConnection() throws Exception {
        inBothModes(BOUND.toMillis() + "ms", StallingHttpServer.Mode.stallAfterChunks(2, dialect), List.of(), null,
                turn -> {
                    assertTrue(turn.awaitSettled(BOUND.plus(GRACE).plus(connectAllowance())),
                            turn.label() + " stalled turn did not settle");
                    assertTimedOutOnce(turn);
                    assertReleased(turn);
                });
    }

    /** (d): a cancel during the stall settles promptly, releases, and writes no error. */
    void cancelDuringTheStall() throws Exception {
        // A long bound: the cancel, not the fire, must end this turn.
        inBothModes("30s", StallingHttpServer.Mode.stallAfterChunks(1, dialect), List.of(), null, turn -> {
            assertTrue(turn.server.awaitRequest(COMPLETE), turn.label() + " never reached the server");
            var unary = turn.fixture.unaryResidual();
            if (unary != null) {
                // No chunk of an unfinished answer reaches a unary call's session.
                logger.warn("{} does not stream its model call: {}", turn.label(), unary);
            } else {
                awaitText(turn);
            }
            turn.cancel();
            assertTrue(turn.awaitSettled(GRACE),
                    turn.label() + " cancel during a stall did not settle within " + GRACE);
            assertEquals(List.of(), turn.session.errors,
                    turn.label() + " a cancel is not an error: no error frame may reach the client");
            assertReleased(turn);
        });
    }

    /** (e): keep-alives with no content for three times the bound are not idle time. */
    void keepalivesAreActivity() throws Exception {
        inBothModes(BOUND.toMillis() + "ms",
                StallingHttpServer.Mode.keepaliveEvery(BOUND.dividedBy(3), LONG, dialect), List.of(), null,
                turn -> {
                    var residual = turn.fixture.keepaliveResidual();
                    if (residual != null) {
                        logger.warn("{} does not see keep-alives: {}", turn.label(), residual);
                        abort(turn.label() + " does not see keep-alives: " + residual);
                    }
                    assertCompletedWithoutTimeout(turn, "keep-alives for " + LONG);
                });
    }

    /** (f): a tool that runs for three times the bound is not idle time. */
    void slowToolIsNotIdle() throws Exception {
        var tool = ToolDefinition.builder("slow_lookup", "A lookup that takes a while")
                .parameter("city", "the city", "string", false)
                .executor(args -> {
                    Thread.sleep(LONG.toMillis());
                    return "sunny";
                })
                .build();
        inBothModes(BOUND.toMillis() + "ms", StallingHttpServer.Mode.toolCallThenText("slow_lookup", dialect),
                List.of(tool), null,
                turn -> assertCompletedWithoutTimeout(turn, "a tool that ran for " + LONG));
    }

    /** (g): an approval resolved after three times the bound is not idle time. */
    void slowApprovalIsNotIdle() throws Exception {
        var ran = new AtomicBoolean();
        var tool = ToolDefinition.builder("guarded_lookup", "A lookup that needs approval")
                .parameter("city", "the city", "string", false)
                .executor(args -> {
                    ran.set(true);
                    return "sunny";
                })
                .requiresApproval("Look it up?")
                .build();
        ApprovalStrategy slowApprover = (approval, session) -> {
            try {
                Thread.sleep(LONG.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalStrategy.ApprovalOutcome.DENIED;
            }
            return ApprovalStrategy.ApprovalOutcome.APPROVED;
        };
        inBothModes(BOUND.toMillis() + "ms", StallingHttpServer.Mode.toolCallThenText("guarded_lookup", dialect),
                List.of(tool), slowApprover, turn -> {
                    assertCompletedWithoutTimeout(turn, "an approval resolved after " + LONG);
                    assertTrue(ran.get(), turn.label() + " the approved tool never ran");
                });
    }

    /** (h): with the bound off, a stall is never timed out, and a cancel still releases. */
    void boundOffNeverTimesOut() throws Exception {
        inBothModes("0", StallingHttpServer.Mode.stallAfterChunks(1, dialect), List.of(), null, turn -> {
            assertTrue(turn.server.awaitRequest(COMPLETE), turn.label() + " never reached the server");
            assertFalse(turn.awaitSettled(LONG),
                    turn.label() + " ended a stalled turn with the bound off (knob 0): "
                            + turn.session.errors);
            assertEquals(List.of(), turn.session.errors, turn.label() + " timed out with the bound off");
            turn.cancel();
            assertTrue(turn.awaitSettled(GRACE), turn.label() + " cancel with the bound off did not settle");
            assertReleased(turn);
        });
    }

    // -- assertions --

    private void assertTimedOutOnce(Turn turn) {
        var session = turn.session;
        assertEquals(1, session.errors.size(),
                turn.label() + " a stalled turn ends with exactly one error; got " + session.errors
                        + (turn.thrown != null ? " (caller saw " + turn.thrown + ")" : ""));
        assertInstanceOf(TimeoutException.class, session.errors.getFirst(),
                turn.label() + " the stall's error is the idle TimeoutException");
        assertTrue(session.errors.getFirst().getMessage().contains(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY),
                turn.label() + " the timeout names the knob: " + session.errors.getFirst().getMessage());
        assertEquals(0, session.completions.get(), turn.label() + " a timed-out turn must not also complete");
        assertEquals(List.of(), session.textAfterTerminal,
                turn.label() + " text reached the client after the timeout");
        assertEquals(1, turn.listener.errors.size(),
                turn.label() + " listeners see onError once: " + turn.listener.errors);
        assertEquals(0, turn.listener.completions.get(), turn.label() + " listeners must not see onCompletion");
    }

    private void assertCompletedWithoutTimeout(Turn turn, String what) throws InterruptedException {
        assertTrue(turn.awaitSettled(COMPLETE), turn.label() + " did not settle after " + what);
        assertEquals(List.of(), turn.session.errors, turn.label() + " timed out on " + what);
        assertEquals(1, turn.session.completions.get(), turn.label() + " did not complete after " + what);
        assertTrue(turn.session.text().contains(StallingHttpServer.CHUNK_TEXT),
                turn.label() + " the answer never reached the session: " + turn.session.text());
    }

    private void assertReleased(Turn turn) throws InterruptedException {
        if (!turn.fixture.releases()) {
            logger.warn("{} leaves the stalled connection to the provider: {}", turn.label(),
                    turn.fixture.residual());
            abort(turn.label() + " leaves the stalled connection to the provider: " + turn.fixture.residual());
        }
        assertTrue(turn.server.clientClosed(RELEASE),
                turn.label() + " the turn ended but the stalled connection stayed open " + RELEASE
                        + " later: the runtime declares releases() but never closed it");
    }

    private static void awaitText(Turn turn) throws InterruptedException {
        var deadline = System.nanoTime() + COMPLETE.toNanos();
        while (turn.session.text().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(turn.session.text().isEmpty(), turn.label() + " the first chunk never reached the session");
    }

    /** Time the first connection and a client's own setup may take on a loaded runner. */
    private static Duration connectAllowance() {
        return Duration.ofSeconds(1);
    }

    /** Records the order of what reaches the client. */
    static final class IdleSession implements StreamingSession {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<String> text = new CopyOnWriteArrayList<>();
        final List<String> textAfterTerminal = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        private volatile boolean closed;

        @Override public String sessionId() { return "stream-idle-contract"; }

        @Override
        public void send(String chunk) {
            (closed ? textAfterTerminal : text).add(chunk);
        }

        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }

        @Override
        public void complete() {
            completions.incrementAndGet();
            closed = true;
        }

        @Override
        public void complete(String summary) {
            if (summary != null && !summary.isEmpty()) {
                send(summary);
            }
            complete();
        }

        @Override
        public void error(Throwable t) {
            errors.add(t);
            closed = true;
        }

        @Override public boolean isClosed() { return closed; }
        @Override public boolean hasErrored() { return !errors.isEmpty(); }

        String text() {
            return String.join("", text);
        }
    }

    static final class IdleListener implements AgentLifecycleListener {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();

        @Override public void onCompletion(AgentExecutionContext context) { completions.incrementAndGet(); }
        @Override public void onError(AgentExecutionContext context, Throwable error) { errors.add(error); }
    }
}
