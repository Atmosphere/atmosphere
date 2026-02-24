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
 * Server-Sent Events (SSE) {@link org.atmosphere.wasync.Transport} using {@link HttpClient}.
 * Parses SSE protocol frames ({@code data:}, {@code event:}, {@code id:}).
 */
public class SSETransport extends StreamTransport {

    public SSETransport(HttpClient httpClient, Options options) {
        super(httpClient, options);
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.SSE;
    }

    @Override
    public CompletableFuture<Void> connect(URI uri, Request request) {
        this.decoders = request.decoders();
        this.resolver = request.functionResolver();

        var reqBuilder = HttpRequest.newBuilder(uri);
        request.headers().forEach((name, values) -> values.forEach(v -> reqBuilder.header(name, v)));
        reqBuilder.header("Accept", "text/event-stream");
        reqBuilder.GET();

        return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
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

                    readThread = Thread.ofVirtual().name("wasync-sse").start(() -> {
                        try (var reader = new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                            var dataBuffer = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data:")) {
                                    var data = line.substring(5).stripLeading();
                                    dataBuffer.append(data);
                                } else if (line.isEmpty() && !dataBuffer.isEmpty()) {
                                    // Empty line marks end of an event
                                    dispatchMessage(Event.MESSAGE, dataBuffer.toString(),
                                            decoders, resolver);
                                    dataBuffer.setLength(0);
                                }
                                // Ignore event:, id:, retry: for now
                            }
                        } catch (Exception e) {
                            if (status != Socket.STATUS.CLOSE) {
                                onThrowable(e);
                            }
                        } finally {
                            if (status != Socket.STATUS.CLOSE) {
                                status = Socket.STATUS.CLOSE;
                                dispatchEvent(Event.CLOSE, "");
                            }
                        }
                    });
                })
                .exceptionally(t -> {
                    onThrowable(t);
                    return null;
                });
    }
}
