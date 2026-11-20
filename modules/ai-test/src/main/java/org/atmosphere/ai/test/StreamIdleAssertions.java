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
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream idle bound against a runtime whose model is a stub that goes
 * silent (it emits nothing more until it is cancelled), for a runtime that
 * cannot be pointed at a {@link StallingHttpServer}: the turn ends with exactly
 * one {@link TimeoutException}, in {@link AgentRuntime#executeWithHandle} and in
 * a synchronous {@link AgentRuntime#execute} on a virtual thread, within the
 * bound plus a grace, no completion, listeners told at most once (never of a
 * completion), and the stub's own
 * release primitive fired. The runtime is attached to an environment of its own
 * carrying the bound, never the static API.
 */
public final class StreamIdleAssertions {

    /** The bound the assertions attach. */
    public static final Duration BOUND = StreamIdleContract.BOUND;

    private StreamIdleAssertions() {
    }

    /**
     * Asserts that a silent model ends the turn once, in both modes.
     *
     * @param runtimes     builds a fresh runtime wired to the silent stub for the
     *                     environment it is given (to bind a native client there,
     *                     for one); the assertion attaches the runtime to it
     * @param context      the turn's context
     * @param releaseLabel what {@code releases} counts, quoted in failures
     * @param releases     how many times the stub's release primitive fired so far;
     *                     {@code null} when the stub cannot observe it
     */
    public static void assertSilentModelEndsTheTurnOnce(Function<AiEnvironment, AgentRuntime> runtimes,
                                                        AgentExecutionContext context,
                                                        String releaseLabel, IntSupplier releases)
            throws Exception {
        var environments = new ArrayList<AiEnvironment>();
        try {
            int expectedReleases = 0;
            for (var mode : StreamIdleContract.Mode.values()) {
                var environment = AiEnvironment.create(
                        Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, BOUND.toMillis() + "ms")::get);
                environments.add(environment);
                var runtime = runtimes.apply(environment);
                runtime.attach(environment);
                var session = new StreamIdleContract.IdleSession();
                var listener = new StreamIdleContract.IdleListener();
                var listeners = new ArrayList<>(context.listeners());
                listeners.add(listener);
                var turnContext = context.withListeners(List.copyOf(listeners));
                var settle = BOUND.plus(StreamIdleContract.GRACE).plus(Duration.ofSeconds(1));
                var label = runtime.name() + " [" + mode + "]";
                if (mode == StreamIdleContract.Mode.HANDLE) {
                    var handle = runtime.executeWithHandle(turnContext, session);
                    try {
                        handle.whenDone().get(settle.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.ExecutionException expected) {
                        // The fire settles the handle with the timeout.
                    }
                    assertTrue(handle.isDone(), label + " the stalled turn's handle did not settle within " + settle);
                } else {
                    var caller = Thread.ofVirtual().start(() -> runtime.execute(turnContext, session));
                    assertTrue(caller.join(settle), label + " the stalled sync turn did not return within " + settle);
                }
                assertEquals(1, session.errors.size(), label + " one error ends a stalled turn: " + session.errors);
                assertInstanceOf(TimeoutException.class, session.errors.getFirst(), label);
                assertEquals(0, session.completions.get(), label + " a timed-out turn must not also complete");
                assertEquals(List.of(), session.textAfterTerminal, label + " text after the timeout");
                // A runtime that fires lifecycle listeners tells them once; one
                // that fires none (Koog, Embabel) must not report a completion.
                assertTrue(listener.errors.size() <= 1, label + " listeners see onError at most once: "
                        + listener.errors);
                assertEquals(0, listener.completions.get(), label + " listeners must not see onCompletion");
                if (releases != null) {
                    expectedReleases++;
                    var deadline = System.nanoTime() + StreamIdleContract.RELEASE.toNanos();
                    while (releases.getAsInt() < expectedReleases && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertEquals(expectedReleases, releases.getAsInt(),
                            label + " the fire did not reach the model call's release (" + releaseLabel + ")");
                }
            }
        } finally {
            environments.forEach(AiEnvironment::close);
        }
    }
}
