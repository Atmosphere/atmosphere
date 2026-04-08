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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous {@link StreamingSession} adapter that collects streamed text
 * into a buffer and blocks until completion. Used by {@link AgentRuntime#generate}
 * and any integration that needs a one-shot LLM response.
 *
 * <p>Thread-safe: {@link #send} may be called from any thread; {@link #await}
 * blocks the calling thread until {@link #complete()} or {@link #error} is invoked.</p>
 */
public final class CollectingSession implements StreamingSession {

    private final StringBuffer buffer = new StringBuffer();
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile Throwable failure;
    private final String sessionId;

    public CollectingSession() {
        this("collecting-" + System.nanoTime());
    }

    public CollectingSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String sessionId() { return sessionId; }

    @Override
    public void send(String text) {
        buffer.append(text);
    }

    @Override
    public void sendMetadata(String key, Object value) { }

    @Override
    public void progress(String message) { }

    @Override
    public void complete() {
        if (!closed) {
            closed = true;
            latch.countDown();
        }
    }

    @Override
    public void complete(String summary) {
        if (summary != null) {
            buffer.append(summary);
        }
        complete();
    }

    @Override
    public void error(Throwable t) {
        if (!closed) {
            closed = true;
            failure = t;
            latch.countDown();
        }
    }

    @Override
    public boolean isClosed() { return closed; }

    /**
     * Block until the session completes or the timeout expires.
     *
     * @param timeout maximum wait time
     * @return true if completed within the timeout, false if timed out
     */
    public boolean await(Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The accumulated response text. */
    public String text() {
        return buffer.toString();
    }

    /** The failure cause, or null if completed successfully. */
    public Throwable failure() {
        return failure;
    }

    /** Whether the session completed with an error. */
    public boolean failed() {
        return failure != null;
    }
}
