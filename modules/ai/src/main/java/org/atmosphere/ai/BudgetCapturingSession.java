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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-call budget enforcement decorator. Sits in the
 * {@link AiPipeline} session-decorator stack just after
 * {@link MetricsCapturingSession} so token/step events flow through it
 * exactly once, regardless of which runtime is dispatching.
 *
 * <p>Token accounting taps {@link StreamingSession#usage(TokenUsage)} —
 * runtimes call this once per chat completion (or once per tool-loop
 * round-trip), so accumulating across the call yields the cumulative
 * token spend. A "step" is one such {@code usage()} callback. Wall clock
 * is sampled lazily at the boundary of each session method.</p>
 *
 * <p>When any limit trips, the decorator routes an
 * {@link AiBudgetExceededException} through
 * {@link StreamingSession#error(Throwable)} — matching the
 * guardrail-block path in {@link AiPipeline} — and short-circuits every
 * subsequent {@code send}/{@code usage}/{@code progress}/{@code emit}/
 * {@code complete} call so the runtime cannot keep accumulating spend
 * after the breach. The first breach wins; subsequent calls are no-ops
 * to keep the wire protocol's lifecycle clean (one terminal frame, not
 * a flurry).</p>
 */
class BudgetCapturingSession extends DelegatingStreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(BudgetCapturingSession.class);

    /**
     * Process-wide single-thread scheduler for wall-clock deadline tasks.
     * Daemon-threaded so it never blocks JVM shutdown. Each wall-clock-bounded
     * call schedules exactly one task at session construction and cancels it
     * when the session terminates (success, error, or token-limit trip).
     */
    private static final ScheduledExecutorService WALL_CLOCK_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "atmosphere-ai-budget-wall-clock");
                t.setDaemon(true);
                return t;
            });

    private final AiBudget budget;
    private final Instant startedAt;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    /**
     * Cancel hook supplied by {@link AiPipeline} after the runtime handle is
     * bound. Invoked on a wall-clock or token trip so a hung provider's
     * runtime handle is cancelled rather than just left dangling.
     * {@code null} until {@link #setOnTrip(Runnable)} is called; if the
     * budget trips before setup completes, the cancel hook is skipped (the
     * trip still routes an error frame, which is the minimum contract).
     */
    private final AtomicReference<Runnable> onTrip = new AtomicReference<>();

    /**
     * Active wall-clock deadline task. {@code null} when the budget is not
     * wall-clock-bounded. Cancelled on terminal session methods so the
     * scheduler thread is freed promptly even when the call finishes well
     * before the deadline.
     */
    private final ScheduledFuture<?> wallClockTask;

    BudgetCapturingSession(StreamingSession delegate, AiBudget budget) {
        super(delegate);
        this.budget = Objects.requireNonNull(budget, "budget");
        if (!budget.enforced()) {
            throw new IllegalArgumentException(
                    "BudgetCapturingSession requires an enforced budget; got UNLIMITED. "
                            + "Skip the decorator entirely instead of installing a no-op.");
        }
        this.startedAt = Instant.now();

        // Schedule the wall-clock deadline up front. Without this, a provider
        // that hangs silently after dispatch would never call back into the
        // session, the lazy boundary sampling would never fire, and the
        // deadline would not be enforced — turning the wall-clock budget into
        // a soft suggestion rather than a hard cap.
        var wallClock = budget.maxWallClock();
        if (wallClock != null && !wallClock.isZero() && !wallClock.isNegative()) {
            this.wallClockTask = WALL_CLOCK_SCHEDULER.schedule(
                    () -> {
                        var elapsed = Duration.between(startedAt, Instant.now()).toMillis();
                        trip(new AiBudgetExceededException(
                                AiBudgetExceededException.Reason.WALL_CLOCK,
                                elapsed, wallClock.toMillis()));
                    },
                    wallClock.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.wallClockTask = null;
        }
    }

    /**
     * Bind a cancel hook (typically {@code handle::cancel}) so a budget trip
     * cancels the runtime handle in addition to routing an error frame.
     * Invoked by {@link AiPipeline} once {@code runtime.executeWithHandle}
     * returns. Idempotent: only the first non-null hook is retained.
     */
    void setOnTrip(Runnable hook) {
        if (hook != null) {
            onTrip.compareAndSet(null, hook);
            // If we already tripped before the hook was bound, fire it now.
            if (tripped.get()) {
                hook.run();
            }
        }
    }

    @Override
    public void send(String text) {
        if (checkWallClockTripped()) {
            return;
        }
        delegate.send(text);
    }

    @Override
    public void sendContent(Content content) {
        if (checkWallClockTripped()) {
            return;
        }
        delegate.sendContent(content);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (tripped.get()) {
            return;
        }
        delegate.sendMetadata(key, value);
    }

    @Override
    public void usage(TokenUsage usage) {
        if (tripped.get() || usage == null) {
            if (!tripped.get()) {
                delegate.usage(usage);
            }
            return;
        }

        // Forward usage first so observers / metrics see the count even on
        // the call that breaks the bank — the breach is data, not a reason
        // to drop the metric.
        delegate.usage(usage);

        var newInput = inputTokens.addAndGet(Math.max(0L, usage.input()));
        var newOutput = outputTokens.addAndGet(Math.max(0L, usage.output()));
        var reportedTotal = usage.total() > 0 ? usage.total() : usage.input() + usage.output();
        var newTotal = totalTokens.addAndGet(Math.max(0L, reportedTotal));
        var newSteps = steps.incrementAndGet();

        if (budget.maxInputTokens() > 0 && newInput > budget.maxInputTokens()) {
            trip(new AiBudgetExceededException(
                    AiBudgetExceededException.Reason.INPUT_TOKENS,
                    newInput, budget.maxInputTokens()));
            return;
        }
        if (budget.maxOutputTokens() > 0 && newOutput > budget.maxOutputTokens()) {
            trip(new AiBudgetExceededException(
                    AiBudgetExceededException.Reason.OUTPUT_TOKENS,
                    newOutput, budget.maxOutputTokens()));
            return;
        }
        if (budget.maxTotalTokens() > 0 && newTotal > budget.maxTotalTokens()) {
            trip(new AiBudgetExceededException(
                    AiBudgetExceededException.Reason.TOTAL_TOKENS,
                    newTotal, budget.maxTotalTokens()));
            return;
        }
        if (budget.maxSteps() > 0 && newSteps > budget.maxSteps()) {
            trip(new AiBudgetExceededException(
                    AiBudgetExceededException.Reason.STEPS,
                    newSteps, budget.maxSteps()));
        }
    }

    @Override
    public void progress(String message) {
        if (checkWallClockTripped()) {
            return;
        }
        delegate.progress(message);
    }

    @Override
    public void emit(AiEvent event) {
        if (checkWallClockTripped()) {
            return;
        }
        delegate.emit(event);
    }

    @Override
    public void complete() {
        cancelWallClockTask();
        if (tripped.get()) {
            return;
        }
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        cancelWallClockTask();
        if (tripped.get()) {
            return;
        }
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        cancelWallClockTask();
        // If we tripped, the error has already been routed to the delegate —
        // swallowing this second error keeps the wire protocol's "one
        // terminal frame" invariant intact. We log at TRACE so the
        // suppressed exception is still discoverable per
        // feedback_no_swallow_exceptions.md (never silently drop).
        if (tripped.get()) {
            if (logger.isTraceEnabled()) {
                logger.trace("BudgetCapturingSession suppressing post-trip error", t);
            }
            return;
        }
        delegate.error(t);
    }

    private void cancelWallClockTask() {
        if (wallClockTask != null) {
            wallClockTask.cancel(false);
        }
    }

    /**
     * Sample wall-clock budget at the boundary of each session method.
     * Returns {@code true} when the call must short-circuit (either
     * already tripped or just crossed the wall-clock limit).
     */
    private boolean checkWallClockTripped() {
        if (tripped.get()) {
            return true;
        }
        var wallClock = budget.maxWallClock();
        if (wallClock == null || wallClock.isZero()) {
            return false;
        }
        var elapsed = Duration.between(startedAt, Instant.now());
        if (elapsed.compareTo(wallClock) > 0) {
            trip(new AiBudgetExceededException(
                    AiBudgetExceededException.Reason.WALL_CLOCK,
                    elapsed.toMillis(), wallClock.toMillis()));
            return true;
        }
        return false;
    }

    /** Atomically flip the tripped flag, route the cause through
     * {@link StreamingSession#error(Throwable)} exactly once, fire the
     * pipeline-supplied cancel hook (so the runtime handle is cancelled,
     * not just the wire) and cancel the wall-clock task so its thread is
     * freed. */
    private void trip(AiBudgetExceededException cause) {
        if (tripped.compareAndSet(false, true)) {
            logger.warn("AI budget exceeded ({}): observed={} limit={} — aborting stream",
                    cause.reason(), cause.observed(), cause.limit());
            delegate.error(cause);
            var hook = onTrip.get();
            if (hook != null) {
                try {
                    hook.run();
                } catch (RuntimeException re) {
                    logger.warn("BudgetCapturingSession onTrip hook threw — ignoring", re);
                }
            }
            cancelWallClockTask();
        }
    }
}
