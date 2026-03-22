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
package org.atmosphere.samples.springboot.dentist;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Collects streaming tokens into a complete response string.
 * Thread-safe — used to bridge async streaming to sync channel sends.
 */
public class CollectingSession implements StreamingSession {

    private final StringBuilder buffer = new StringBuilder();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean closed;

    @Override
    public String sessionId() {
        return "channel-collecting";
    }

    @Override
    public void send(String text) {
        buffer.append(text);
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
        done.countDown();
    }

    @Override
    public void complete(String summary) {
        closed = true;
        done.countDown();
    }

    @Override
    public void error(Throwable t) {
        closed = true;
        done.countDown();
    }

    @Override
    public void emit(AiEvent event) {
        switch (event) {
            case AiEvent.TextDelta delta -> send(delta.text());
            case AiEvent.Complete c -> complete();
            case AiEvent.Error e -> error(new RuntimeException(e.message()));
            default -> { }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Blocks until the stream completes (up to 120 seconds).
     */
    public String getResponse() {
        try {
            done.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return buffer.toString();
    }
}
