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
package org.atmosphere.wasync.transport;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.FunctionResolver;
import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link org.atmosphere.wasync.Transport} implementation using {@link java.net.http.WebSocket}.
 */
public class WebSocketTransport extends AbstractTransport implements WebSocket.Listener {

    private final HttpClient httpClient;
    private final Options options;
    private volatile WebSocket webSocket;
    private volatile StringBuilder textBuffer = new StringBuilder();
    private volatile ByteBuffer binaryBuffer = ByteBuffer.allocate(0);
    private List<Decoder<?, ?>> decoders = List.of();
    private FunctionResolver resolver = FunctionResolver.DEFAULT;

    public WebSocketTransport(HttpClient httpClient, Options options) {
        this.httpClient = httpClient;
        this.options = options;
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.WEBSOCKET;
    }

    /**
     * Connect to the given URI.
     */
    public CompletableFuture<Void> connect(URI uri, Request request) {
        this.decoders = request.decoders();
        this.resolver = request.functionResolver();

        var builder = httpClient.newWebSocketBuilder();
        request.headers().forEach((name, values) -> values.forEach(v -> builder.header(name, v)));

        return builder.buildAsync(uri, this).thenAccept(ws -> {
            this.webSocket = ws;
            status = Socket.STATUS.OPEN;
            dispatchEvent(Event.OPEN, ws.toString());
            if (connectFuture != null) {
                connectFuture.complete(null);
            }
        }).exceptionally(t -> {
            onThrowable(t);
            return null;
        });
    }

    /**
     * Send a text message.
     */
    public CompletableFuture<Void> sendText(String text) {
        if (webSocket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("WebSocket not connected"));
        }
        return webSocket.sendText(text, true).thenApply(ws -> null);
    }

    /**
     * Send a binary message.
     */
    public CompletableFuture<Void> sendBinary(byte[] data) {
        if (webSocket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("WebSocket not connected"));
        }
        return webSocket.sendBinary(ByteBuffer.wrap(data), true).thenApply(ws -> null);
    }

    @Override
    public void onOpen(WebSocket ws) {
        logger.debug("WebSocket opened");
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            var message = textBuffer.toString();
            textBuffer = new StringBuilder();
            dispatchMessage(Event.MESSAGE, message, decoders, resolver);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        var combined = ByteBuffer.allocate(binaryBuffer.remaining() + data.remaining());
        combined.put(binaryBuffer);
        combined.put(data);
        combined.flip();
        if (last) {
            var bytes = new byte[combined.remaining()];
            combined.get(bytes);
            binaryBuffer = ByteBuffer.allocate(0);
            dispatchMessage(Event.MESSAGE_BYTES, bytes, decoders, resolver);
        } else {
            binaryBuffer = combined;
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
        ws.sendPong(message);
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        logger.debug("WebSocket closed: {} {}", statusCode, reason);
        status = Socket.STATUS.CLOSE;
        dispatchEvent(Event.CLOSE, reason != null ? reason : "");
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        onThrowable(error);
    }

    @Override
    public void close() {
        status = Socket.STATUS.CLOSE;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (Exception e) {
                logger.debug("Error closing WebSocket", e);
            }
        }
    }
}
