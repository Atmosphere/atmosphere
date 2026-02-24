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

import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Long-polling {@link org.atmosphere.wasync.Transport} using {@link HttpClient}.
 * Sends repeated HTTP requests, each of which blocks until a message is available.
 */
public class LongPollingTransport extends AbstractTransport {

    private final HttpClient httpClient;
    private final Options options;
    private volatile boolean running;

    public LongPollingTransport(HttpClient httpClient, Options options) {
        this.httpClient = httpClient;
        this.options = options;
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.LONG_POLLING;
    }

    /**
     * Start the long-polling loop.
     */
    public CompletableFuture<Void> connect(URI uri, Request request) {
        running = true;
        var decoders = request.decoders();
        var resolver = request.functionResolver();

        var reqBuilder = HttpRequest.newBuilder(uri);
        request.headers().forEach((name, values) -> values.forEach(v -> reqBuilder.header(name, v)));
        reqBuilder.GET();

        return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    int statusCode = response.statusCode();
                    dispatchEvent(Event.STATUS, statusCode);

                    if (statusCode != 200) {
                        onThrowable(new RuntimeException("HTTP " + statusCode));
                        return;
                    }

                    status = Socket.STATUS.OPEN;
                    dispatchEvent(Event.OPEN, response.toString());
                    if (connectFuture != null) {
                        connectFuture.complete(null);
                    }

                    var body = response.body();
                    if (body != null && !body.isEmpty()) {
                        dispatchMessage(Event.MESSAGE, body, decoders, resolver);
                    }

                    // Start polling loop on a virtual thread
                    Thread.ofVirtual().name("wasync-longpoll").start(() -> poll(uri, request));
                })
                .exceptionally(t -> {
                    onThrowable(t);
                    return null;
                });
    }

    private void poll(URI uri, Request request) {
        while (running && status != Socket.STATUS.CLOSE) {
            try {
                var reqBuilder = HttpRequest.newBuilder(uri);
                request.headers().forEach((name, values) -> values.forEach(v -> reqBuilder.header(name, v)));
                reqBuilder.GET();

                var response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var body = response.body();
                    if (body != null && !body.isEmpty()) {
                        dispatchMessage(Event.MESSAGE, body, request.decoders(),
                                request.functionResolver());
                    }
                } else {
                    logger.warn("Long-poll received HTTP {}", response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    onThrowable(e);
                    break;
                }
            }
        }
        if (status != Socket.STATUS.CLOSE) {
            status = Socket.STATUS.CLOSE;
            dispatchEvent(Event.CLOSE, "");
        }
    }

    /**
     * Send data via HTTP POST.
     */
    public CompletableFuture<Void> sendText(String text, URI uri, Request request) {
        var reqBuilder = HttpRequest.newBuilder(uri);
        request.headers().forEach((name, values) -> values.forEach(v -> reqBuilder.header(name, v)));
        reqBuilder.POST(HttpRequest.BodyPublishers.ofString(text));

        return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> null);
    }

    @Override
    public void close() {
        running = false;
        status = Socket.STATUS.CLOSE;
    }
}
