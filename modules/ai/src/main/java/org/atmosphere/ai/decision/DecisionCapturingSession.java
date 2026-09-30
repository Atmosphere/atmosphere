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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sink one decision question is dispatched into: buffers the reply text,
 * keeps the last {@link AiConfidence} the runtime reported (the whole record,
 * so a {@link AiConfidence#decision()} distribution survives), sums token
 * usage, records a failure, and releases a latch on the first terminal event.
 * Events after the terminal one are ignored, so a runtime that keeps writing
 * after its question was abandoned cannot change the recorded reply.
 *
 * <p>The reply buffer is bounded at {@value #MAX_REPLY_CHARS} characters
 * (Correctness Invariant #3). The expected reply is one small JSON object; a
 * runtime that streams past the bound closes the session as
 * {@linkplain #overflowed() overflowed}, and the caller cancels the dispatch
 * and records the question as unparseable instead of buffering more.</p>
 */
final class DecisionCapturingSession implements StreamingSession {

    /** Upper bound on the buffered reply, in characters. */
    static final int MAX_REPLY_CHARS = 16 * 1024;

    private final String sessionId;
    private final StringBuilder text = new StringBuilder();
    private final AtomicReference<AiConfidence> confidence = new AtomicReference<>();
    private final AtomicReference<TokenUsage> usage = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Throwable failure;
    private volatile boolean outputSeen;
    private volatile boolean overflowed;

    DecisionCapturingSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void send(String chunk) {
        if (chunk == null || chunk.isEmpty() || closed.get()) {
            return;
        }
        outputSeen = true;
        append(chunk);
    }

    /** Append within the bound; past it, close the session as overflowed. */
    private void append(String chunk) {
        boolean overflow;
        synchronized (text) {
            overflow = text.length() + chunk.length() > MAX_REPLY_CHARS;
            if (!overflow) {
                text.append(chunk);
            }
        }
        if (overflow) {
            overflowed = true;
            complete();
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
        // Wire metadata has no reader here; the typed confidence and usage
        // records are captured through their own overrides below.
    }

    @Override
    public void progress(String message) {
        // Progress text is not part of the decision reply.
    }

    @Override
    public void usage(TokenUsage reported) {
        if (reported == null || !reported.hasCounts()) {
            return;
        }
        usage.accumulateAndGet(reported, DecisionCapturingSession::sum);
    }

    @Override
    public void confidence(AiConfidence reported) {
        if (reported != null && !closed.get()) {
            confidence.set(reported);
        }
    }

    @Override
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            done.countDown();
        }
    }

    /**
     * A runtime that streamed nothing may hand the full reply over as the
     * summary; one that already streamed it repeats it there, so the summary
     * is used only when nothing was sent.
     */
    @Override
    public void complete(String summary) {
        if (summary != null && !summary.isEmpty() && !outputSeen && !closed.get()) {
            append(summary);
        }
        complete();
    }

    @Override
    public void error(Throwable t) {
        if (closed.compareAndSet(false, true)) {
            failure = t != null ? t : new IllegalStateException("runtime reported an error without a cause");
            done.countDown();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean hasErrored() {
        return failure != null;
    }

    /**
     * Wait for the terminal event.
     *
     * @return {@code true} when the session terminated within the bound
     * @throws InterruptedException when the waiting carrier is interrupted
     *                              (the question was abandoned)
     */
    boolean await(long nanos) throws InterruptedException {
        return done.await(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    /** Close without a verdict — used when the question is abandoned. */
    void abandon() {
        complete();
    }

    String text() {
        synchronized (text) {
            return text.toString();
        }
    }

    /** The last confidence the runtime reported, or {@code null}. */
    AiConfidence confidence() {
        return confidence.get();
    }

    /** Usage summed over every report, or {@code null} when none carried counts. */
    TokenUsage usage() {
        return usage.get();
    }

    /** The failure the runtime reported, or {@code null}. */
    Throwable failure() {
        return failure;
    }

    /** Whether the reply exceeded {@link #MAX_REPLY_CHARS} and the session closed on it. */
    boolean overflowed() {
        return overflowed;
    }

    /** Whether any reply text arrived. */
    boolean outputSeen() {
        return outputSeen;
    }

    static TokenUsage sum(TokenUsage a, TokenUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new TokenUsage(a.input() + b.input(), a.output() + b.output(),
                a.cachedInput() + b.cachedInput(), a.total() + b.total(),
                a.model() != null ? a.model() : b.model());
    }
}
