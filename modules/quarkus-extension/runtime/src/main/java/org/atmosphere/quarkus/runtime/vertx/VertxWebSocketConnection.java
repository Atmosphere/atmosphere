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

import io.vertx.core.http.ServerWebSocket;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One upgraded WebSocket: opens it on the {@link WebSocketProcessor}, then feeds
 * each whole message to the processor in arrival order from a single virtual
 * thread, and closes it exactly once. Frames are queued from the event loop; the
 * socket is paused when the queue reaches its high-water mark and resumed once
 * the worker catches up, so a slow application handler slows its own client
 * instead of growing memory (Correctness Invariant #3).
 */
final class VertxWebSocketConnection {

    private static final Logger logger = LoggerFactory.getLogger(VertxWebSocketConnection.class);

    static final int CAPACITY = 128;
    static final int HIGH_WATER = 64;
    static final int LOW_WATER = 16;

    private final ServerWebSocket socket;
    private final VertxWebSocket webSocket;
    private final WebSocketProcessor processor;
    private final AtmosphereRequest request;
    private final BlockingQueue<Runnable> inbox = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    VertxWebSocketConnection(ServerWebSocket socket, VertxWebSocket webSocket,
                             WebSocketProcessor processor, AtmosphereRequest request) {
        this.socket = socket;
        this.webSocket = webSocket;
        this.processor = processor;
        this.request = request;
    }

    /** Wire the socket handlers and start the per-connection worker. */
    void start(ThreadFactory threads) {
        socket.pause();
        socket.textMessageHandler(text -> enqueue(() -> processor.invokeWebSocketProtocol(webSocket, text)));
        socket.binaryMessageHandler(buffer -> {
            var bytes = buffer.getBytes();
            enqueue(() -> processor.invokeWebSocketProtocol(webSocket, bytes, 0, bytes.length));
        });
        socket.exceptionHandler(t -> logger.trace("WebSocket {} error", webSocket.uuid(), t));
        socket.closeHandler(v -> {
            webSocket.markClosed();
            var code = socket.closeStatusCode() == null ? 1005 : socket.closeStatusCode();
            enqueueClose(code);
        });
        threads.newThread(this::run).start();
    }

    private void enqueue(Runnable task) {
        if (!inbox.offer(task)) {
            // The high-water pause should make this unreachable; if a burst still
            // overflows, refuse the connection loudly instead of dropping frames.
            logger.warn("WebSocket {} inbox overflow; closing with 1013", webSocket.uuid());
            webSocket.close(1013, "Try again later");
            return;
        }
        if (inbox.size() >= HIGH_WATER && paused.compareAndSet(false, true)) {
            socket.pause();
        }
    }

    private void enqueueClose(int code) {
        // Runs on the event loop: hand the (possibly waiting) put to a virtual
        // thread so the close lands after every pending message without blocking.
        Thread.ofVirtual().start(() -> {
            try {
                inbox.put(() -> closeOnce(code));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void run() {
        try {
            processor.open(webSocket, request,
                    AtmosphereResponseImpl.newInstance(webSocket.config(), request, webSocket));
        } catch (Exception e) {
            logger.warn("Failed to open WebSocket {}", webSocket.uuid(), e);
            webSocket.close(1011, "Open failed");
            closeOnce(1011);
            return;
        }
        socket.resume();
        while (!closed.get()) {
            Runnable task;
            try {
                task = inbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeOnce(1001);
                return;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.warn("WebSocket {} message handling failed", webSocket.uuid(), e);
            }
            if (paused.get() && inbox.size() <= LOW_WATER && paused.compareAndSet(true, false)) {
                socket.resume();
            }
        }
    }

    private void closeOnce(int code) {
        if (closed.compareAndSet(false, true)) {
            webSocket.markClosed();
            try {
                processor.close(webSocket, code);
            } catch (RuntimeException e) {
                logger.debug("WebSocket {} close notification failed", webSocket.uuid(), e);
            }
            inbox.clear();
        }
    }
}
