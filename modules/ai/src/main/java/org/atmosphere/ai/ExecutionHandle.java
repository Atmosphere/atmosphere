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

import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Cooperative cancellation handle returned by
 * {@link AgentRuntime#executeWithHandle(AgentExecutionContext, StreamingSession)}.
 *
 * <p>Phase 2 of the unified {@code @Agent} API closes Correctness Invariant #2
 * (Terminal Path Completeness) by giving callers a first-class way to abort an
 * in-flight chat completion: the handle wraps each runtime's native cancel
 * primitive (Reactor {@code Disposable}, LC4j {@code Response} cancel, Koog
 * {@code Job.cancel()}, ADK {@code Runner.close()}, Built-in HttpClient request
 * cancel) so the same external API cancels any backend.</p>
 *
 * <p>Calling {@link #cancel()} must be idempotent and safe on any thread. The
 * {@link #whenDone()} future resolves once the runtime has fully terminated
 * (whether via normal completion, error, or cancellation) so consumers can
 * await release of downstream resources.</p>
 */
public interface ExecutionHandle {

    /**
     * Request cancellation of the in-flight execution. Idempotent — subsequent
     * calls are no-ops. Implementations should fire the runtime's native cancel
     * primitive and then complete {@link #whenDone()} once the native pipeline
     * has observed the cancellation.
     */
    void cancel();

    /**
     * @return {@code true} once the execution has terminated (via completion,
     * error, or cancellation).
     */
    boolean isDone();

    /**
     * @return a future that resolves when the execution terminates. Consumers
     * can chain resource cleanup on this without blocking the calling thread.
     */
    CompletableFuture<Void> whenDone();

    /**
     * A handle representing an execution that has already terminated. Used as
     * the default return value for runtimes that do not override
     * {@link AgentRuntime#executeWithHandle} — cancel is a no-op and
     * {@link #whenDone()} is already complete.
     */
    static ExecutionHandle completed() {
        return COMPLETED;
    }

    ExecutionHandle COMPLETED = new ExecutionHandle() {
        private final CompletableFuture<Void> done = CompletableFuture.completedFuture(null);

        @Override
        public void cancel() {
            // Already done — nothing to cancel.
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public CompletableFuture<Void> whenDone() {
            return done;
        }
    };

    /**
     * Simple mutable handle useful for runtimes that only need a CAS-guarded
     * cancel flag plus a {@link CompletableFuture} the runtime completes when
     * its native pipeline terminates. Runtimes with a richer native primitive
     * should wrap it directly instead of using this.
     *
     * <p><b>Terminal reason race:</b> {@link CompletableFuture} is
     * first-write-wins, so if {@link #cancel()} and a natural
     * {@link #completeExceptionally(Throwable)} race, observers chained on
     * {@link #whenDone()} see whichever completion fired first. A real error
     * that arrives strictly after {@code cancel()} is silently dropped. If
     * you need to distinguish "cancelled" from "errored" in telemetry,
     * consult {@link #isCancelled()} explicitly inside your
     * {@code whenComplete} callback — never rely on the cause type alone.</p>
     */
    final class Settable implements ExecutionHandle {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final Runnable nativeCancel;

        /**
         * @param nativeCancel the runtime's native cancel primitive; called at
         *                     most once on the first {@link #cancel()} call.
         *                     May be {@code null} for runtimes without a native
         *                     cancel path — in that case {@code cancel()} just
         *                     sets the flag and the runtime's polling loop
         *                     observes it.
         */
        public Settable(Runnable nativeCancel) {
            this.nativeCancel = nativeCancel;
        }

        /** Mark the execution as terminated. Runtimes call this from their completion hook. */
        public void complete() {
            done.complete(null);
        }

        /** Mark the execution as terminated with an error. */
        public void completeExceptionally(Throwable t) {
            done.completeExceptionally(t);
        }

        /** @return {@code true} if {@link #cancel()} has been invoked (polling helper for runtimes). */
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (nativeCancel != null) {
                    try {
                        nativeCancel.run();
                    } catch (Exception e) {
                        // Native cancel is best-effort (the caller will block
                        // on whenDone() regardless), but record the failure at
                        // TRACE so post-mortem and observability tooling can
                        // see which runtimes failed to unwind. Swallowing
                        // silently violates the project's no-swallow rule.
                        LoggerFactory.getLogger(ExecutionHandle.class)
                                .trace("ExecutionHandle native cancel threw {}: {}",
                                        e.getClass().getSimpleName(), e.getMessage(), e);
                    }
                }
                done.complete(null);
            }
        }

        @Override
        public boolean isDone() {
            return done.isDone();
        }

        @Override
        public CompletableFuture<Void> whenDone() {
            return done;
        }
    }
}
