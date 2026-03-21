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
package org.atmosphere.samples.channels;

import org.atmosphere.ai.StreamingSession;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A {@link StreamingSession} that collects streaming tokens into a string.
 * Thread-safe: {@link #getResponse()} blocks until streaming completes.
 */
class CollectingSession implements StreamingSession {

    private final StringBuilder buffer = new StringBuilder();
    private final CountDownLatch latch = new CountDownLatch(1);
    private final String id = UUID.randomUUID().toString();
    private volatile boolean closed;

    @Override
    public String sessionId() {
        return id;
    }

    @Override
    public void send(String text) {
        synchronized (buffer) {
            buffer.append(text);
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
    }

    @Override
    public void progress(String message) {
    }

    @Override
    public void complete() {
        closed = true;
        latch.countDown();
    }

    @Override
    public void complete(String summary) {
        closed = true;
        latch.countDown();
    }

    @Override
    public void error(Throwable t) {
        closed = true;
        latch.countDown();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Blocks until streaming completes and returns the collected response.
     */
    String getResponse() {
        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (buffer) {
            return buffer.toString();
        }
    }
}
