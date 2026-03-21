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

/**
 * A {@link StreamingSession} that collects streaming tokens into a string.
 * Used to bridge streaming AI responses to channels that don't support
 * incremental delivery (WhatsApp, Messenger) — collect first, send once.
 */
class CollectingSession implements StreamingSession {

    private final StringBuilder buffer = new StringBuilder();
    private final String id = UUID.randomUUID().toString();
    private volatile boolean closed;

    @Override
    public String sessionId() {
        return id;
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
    }

    @Override
    public void complete(String summary) {
        closed = true;
    }

    @Override
    public void error(Throwable t) {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    String getResponse() {
        return buffer.toString();
    }
}
