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
package org.atmosphere.samples.springboot.passivation;

import org.atmosphere.ai.StreamingSession;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A synchronous {@link StreamingSession} sink the application drives a resumed
 * run into. It accumulates the streamed text and the metadata frames the
 * runtime emits, and blocks until the run reaches a terminal state.
 *
 * <p>Atmosphere ships {@code CollectingSession} for text-only collection;
 * this sample needs the runtime's metadata too (restored history size,
 * continuation flag), so it captures both. In a real deployment the resume
 * would instead push into the live {@code StreamingSession} of a reconnected
 * client; here a synchronous sink keeps the REST flow and the delivery test
 * deterministic and offline.</p>
 */
final class CapturingSession implements StreamingSession {

    private final String sessionId;
    private final StringBuilder text = new StringBuilder();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile Throwable failure;

    CapturingSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void send(String chunk) {
        if (chunk != null) {
            text.append(chunk);
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
    }

    @Override
    public void progress(String message) {
        // Not surfaced by this sample's REST response.
    }

    @Override
    public void complete() {
        terminate(null);
    }

    @Override
    public void complete(String summary) {
        if (summary != null) {
            text.append(summary);
        }
        terminate(null);
    }

    @Override
    public void error(Throwable t) {
        terminate(t);
    }

    private void terminate(Throwable t) {
        if (!closed) {
            closed = true;
            failure = t;
            done.countDown();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean hasErrored() {
        return failure != null;
    }

    /** Block until the resumed run terminates or the timeout expires. */
    boolean await(Duration timeout) {
        try {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    String text() {
        return text.toString();
    }

    Map<String, Object> metadata() {
        return Map.copyOf(metadata);
    }

    Throwable failure() {
        return failure;
    }
}
