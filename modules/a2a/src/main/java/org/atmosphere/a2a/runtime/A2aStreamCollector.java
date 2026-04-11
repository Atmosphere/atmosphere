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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared {@link StreamingSession} adapter for A2A task runtimes. Collects
 * streamed text into a thread-safe buffer and writes it as the A2A task
 * result on completion. Extracted from a copy/paste pair that previously
 * lived inline in {@code AgentProcessor} and {@code CoordinatorProcessor};
 * those copies drifted to use {@code StringBuilder} vs {@code StringBuffer}
 * and both used a non-atomic {@code if (!finalized) finalized = true} guard
 * that allowed double-finalization under concurrent send/complete races.
 *
 * <p>Concurrency guarantees:</p>
 * <ul>
 *   <li>{@code send()} is safe under concurrent writes — the backing
 *       {@link StringBuffer} is synchronized.</li>
 *   <li>Completion/error is idempotent via {@link AtomicBoolean#compareAndSet} —
 *       racing {@code complete()} and {@code error()} callers cause exactly
 *       one terminal transition; the later callers are no-ops.</li>
 * </ul>
 */
public class A2aStreamCollector implements StreamingSession {

    private final TaskContext taskCtx;
    private final AiPipeline pipeline;
    private final StringBuffer buffer = new StringBuffer();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final AtomicBoolean errored = new AtomicBoolean(false);

    public A2aStreamCollector(TaskContext taskCtx, AiPipeline pipeline) {
        this.taskCtx = taskCtx;
        this.pipeline = pipeline;
    }

    @Override
    public String sessionId() {
        return taskCtx.taskId();
    }

    @Override
    public void send(String text) {
        buffer.append(text);
    }

    @Override
    public void stream(String message) {
        if (pipeline != null) {
            // Delegate to the AI pipeline so the message is processed through
            // the LLM and the response streams back through this collector.
            pipeline.execute(taskCtx.taskId(), message, this);
        } else {
            // Fallback: buffer the message as the response text when no
            // pipeline is available.
            buffer.append(message);
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
        // A2A tasks don't propagate metadata; silently ignore.
    }

    @Override
    public void progress(String message) {
        taskCtx.updateStatus(TaskState.WORKING, message);
    }

    @Override
    public void complete() {
        if (finalized.compareAndSet(false, true)) {
            taskCtx.complete(buffer.toString());
            completionLatch.countDown();
        }
    }

    @Override
    public void complete(String summary) {
        if (finalized.compareAndSet(false, true)) {
            taskCtx.complete(summary != null ? summary : buffer.toString());
            completionLatch.countDown();
        }
    }

    @Override
    public void error(Throwable t) {
        errored.set(true);
        if (finalized.compareAndSet(false, true)) {
            taskCtx.fail(t.getMessage());
            completionLatch.countDown();
        }
    }

    @Override
    public boolean isClosed() {
        return finalized.get();
    }

    @Override
    public boolean hasErrored() {
        return errored.get();
    }

    /**
     * Wait for async completion and finalize the task if the prompt method
     * returned without calling {@code complete()} (e.g. a synchronous prompt
     * that just wrote to the session). Safe to call after the latch has
     * already counted down — the finalize check is a CAS.
     *
     * @param timeoutMs maximum milliseconds to wait
     */
    public void awaitAndFinalize(long timeoutMs) {
        try {
            completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (finalized.compareAndSet(false, true)) {
            taskCtx.complete(buffer.toString());
        }
    }

    /** Exposed for subclasses and tests. */
    protected final String buffer() {
        return buffer.toString();
    }

    /** Exposed for subclasses. */
    protected final TaskContext taskCtx() {
        return taskCtx;
    }

    /** Exposed for subclasses. */
    protected final AiPipeline pipeline() {
        return pipeline;
    }
}
