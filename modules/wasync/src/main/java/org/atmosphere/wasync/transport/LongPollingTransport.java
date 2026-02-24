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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Long-polling {@link org.atmosphere.wasync.Transport} using {@link HttpClient}.
 * Sends repeated HTTP requests, each of which blocks until a message is available.
 *
 * <p>Atmosphere suspends the initial GET request and holds the connection open until
 * data is available. Once data is written, the response completes and the client
 * immediately sends a new GET to continue polling.</p>
 */
public class LongPollingTransport extends AbstractTransport {

    private final HttpClient httpClient;
    private final Options options;
    private volatile boolean running;
    private volatile Thread pollThread;

    public LongPollingTransport(HttpClient httpClient, Options options) {
        this.httpClient = httpClient;
        this.options = options;
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.LONG_POLLING;
    }

    /**
     * Start the long-polling loop. OPEN is dispatched immediately since the server
     * may hold the first request indefinitely until data is available.
     */
    public CompletableFuture<Void> connect(URI uri, Request request) {
        running = true;

        // Dispatch OPEN immediately — long-polling "connects" by starting the poll loop
        status = Socket.STATUS.OPEN;
        dispatchEvent(Event.OPEN, "long-polling");
        if (connectFuture != null) {
            connectFuture.complete(null);
        }

        pollThread = Thread.ofVirtual().name("wasync-longpoll").start(() -> poll(uri, request));

        return CompletableFuture.completedFuture(null);
    }

    private void poll(URI uri, Request request) {
        var decoders = request.decoders();
        var resolver = request.functionResolver();

        while (running && status != Socket.STATUS.CLOSE) {
            try {
                var reqBuilder = HttpRequest.newBuilder(uri);
                request.headers().forEach((name, values) ->
                        values.forEach(v -> reqBuilder.header(name, v)));
                reqBuilder.GET();

                var response = httpClient.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    try (var reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        var sb = new StringBuilder();
                        var buf = new char[4096];
                        int n;
                        while ((n = reader.read(buf)) != -1) {
                            sb.append(buf, 0, n);
                        }
                        var body = sb.toString().strip();
                        if (!body.isEmpty()) {
                            dispatchMessage(Event.MESSAGE, body, decoders, resolver);
                        }
                    }
                } else {
                    logger.warn("Long-poll received HTTP {}", response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    logger.debug("Long-poll error", e);
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
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }
}
