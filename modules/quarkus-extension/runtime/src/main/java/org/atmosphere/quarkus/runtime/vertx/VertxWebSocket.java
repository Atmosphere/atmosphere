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
package org.atmosphere.quarkus.runtime.vertx;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.websocket.WebSocket;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Atmosphere {@link WebSocket} over a Vert.x {@link ServerWebSocket}. Text and
 * binary frames are written as whole messages; a writer on a virtual thread waits
 * for the drain signal when the socket's write queue is full (bounded by
 * {@code drainTimeoutMs}, after which the socket is closed with 1008).
 */
final class VertxWebSocket extends WebSocket {

    private final ServerWebSocket socket;
    private final long drainTimeoutMs;
    private volatile boolean open = true;

    VertxWebSocket(AtmosphereConfig config, ServerWebSocket socket, long drainTimeoutMs) {
        super(config);
        this.socket = socket;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    void markClosed() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open && !socket.isClosed();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        ensureOpen();
        socket.writeTextMessage(s);
        awaitDrain();
        return this;
    }

    @Override
    public WebSocket write(byte[] b, int offset, int length) throws IOException {
        ensureOpen();
        socket.writeBinaryMessage(Buffer.buffer().appendBytes(b, offset, length));
        awaitDrain();
        return this;
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            socket.close();
        }
    }

    @Override
    public void close(int statusCode, String reasonText) {
        if (open) {
            open = false;
            socket.close((short) statusCode, reasonText);
        }
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WebSocket closed: " + uuid());
        }
    }

    private void awaitDrain() throws IOException {
        if (!socket.writeQueueFull() || Context.isOnEventLoopThread()) {
            return;
        }
        var drained = new CountDownLatch(1);
        socket.drainHandler(v -> drained.countDown());
        if (!socket.writeQueueFull()) {
            return;
        }
        try {
            if (!drained.await(drainTimeoutMs, TimeUnit.MILLISECONDS)) {
                close(1008, "Slow consumer");
                throw new IOException("Slow consumer: WebSocket write queue did not drain");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for drain", e);
        }
    }
}
