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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP streaming {@link org.atmosphere.wasync.Transport} using {@link HttpClient}.
 * Reads the response body as a continuous stream of lines.
 */
public class StreamTransport extends AbstractTransport {

    protected final HttpClient httpClient;
    protected final Options options;
    protected volatile Thread readThread;
    protected List<Decoder<?, ?>> decoders = List.of();
    protected FunctionResolver resolver = FunctionResolver.DEFAULT;

    public StreamTransport(HttpClient httpClient, Options options) {
        this.httpClient = httpClient;
        this.options = options;
    }

    @Override
    public Request.TRANSPORT name() {
        return Request.TRANSPORT.STREAMING;
    }

    /**
     * Connect and start reading the stream.
     */
    public CompletableFuture<Void> connect(URI uri, Request request) {
        this.decoders = request.decoders();
        this.resolver = request.functionResolver();

        var reqBuilder = HttpRequest.newBuilder(uri);
        request.headers().forEach((name, values) -> values.forEach(v -> reqBuilder.header(name, v)));
        reqBuilder.GET();

        return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    int statusCode = response.statusCode();
                    dispatchEvent(Event.STATUS, statusCode);
                    dispatchEvent(Event.HEADERS, response.headers().map());

                    if (statusCode != 200) {
                        onThrowable(new RuntimeException("HTTP " + statusCode));
                        return;
                    }

                    status = Socket.STATUS.OPEN;
                    dispatchEvent(Event.OPEN, response.toString());
                    if (connectFuture != null) {
                        connectFuture.complete(null);
                    }

                    readThread = Thread.ofVirtual().name("wasync-stream").start(() -> {
                        try (var reader = new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.isEmpty()) {
                                    dispatchMessage(Event.MESSAGE, line, decoders, resolver);
                                }
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
        status = Socket.STATUS.CLOSE;
        if (readThread != null) {
            readThread.interrupt();
        }
    }
}
