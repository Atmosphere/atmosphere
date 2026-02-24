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
package org.atmosphere.wasync.impl;

import org.atmosphere.wasync.Encoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.FunctionBinding;
import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.Transport;
import org.atmosphere.wasync.transport.LongPollingTransport;
import org.atmosphere.wasync.transport.SSETransport;
import org.atmosphere.wasync.transport.StreamTransport;
import org.atmosphere.wasync.transport.WebSocketTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link Socket} implementation with transport fallback and reconnection support.
 */
public class DefaultSocket implements Socket {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSocket.class);

    private final Options options;
    private final List<FunctionBinding> functions = new ArrayList<>();
    private volatile Transport activeTransport;
    private volatile Request request;
    private volatile STATUS status = STATUS.INIT;
    private volatile HttpClient httpClient;
    private volatile boolean ownHttpClient;

    public DefaultSocket(Options options) {
        this.options = options;
    }

    @Override
    public Socket on(Event event, Function<?> function) {
        functions.add(new FunctionBinding(event.name(), function));
        return this;
    }

    @Override
    public Socket on(String functionMessage, Function<?> function) {
        functions.add(new FunctionBinding(functionMessage, function));
        return this;
    }

    @Override
    public Socket on(Function<?> function) {
        functions.add(new FunctionBinding(Event.MESSAGE.name(), function));
        return this;
    }

    @Override
    public Socket open(Request request) {
        return open(request, options.waitBeforeUnlocking(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Socket open(Request request, long timeout, TimeUnit unit) {
        this.request = request;
        initHttpClient();

        var latch = new CountDownLatch(1);
        var transports = request.transport();

        // Register an open listener to count down the latch
        functions.add(new FunctionBinding(Event.OPEN.name(), (Function<Object>) o -> latch.countDown()));

        connectWithFallback(transports, 0);

        try {
            if (!latch.await(timeout, unit)) {
                logger.debug("Open timed out after {} {}", timeout, unit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for connection", e);
        }

        return this;
    }

    private void connectWithFallback(List<Request.TRANSPORT> transports, int index) {
        if (index >= transports.size()) {
            status = STATUS.ERROR;
            logger.error("All transports failed");
            dispatchError(new RuntimeException("All transports failed"));
            return;
        }

        var transportType = transports.get(index);
        var transport = createTransport(transportType);

        for (var binding : functions) {
            transport.registerFunction(binding);
        }

        // Wrap the error handler for fallback
        if (index < transports.size() - 1) {
            var reconnectAttempts = new AtomicInteger(0);
            transport.registerFunction(new FunctionBinding(Event.ERROR.name(), (Function<Object>) e -> {
                if (status == STATUS.INIT && reconnectAttempts.getAndIncrement() == 0) {
                    logger.info("Transport {} failed, falling back to {}",
                            transportType, transports.get(index + 1));
                    connectWithFallback(transports, index + 1);
                }
            }));
        }

        activeTransport = transport;
        status = STATUS.INIT;

        var uri = buildUri(request);

        switch (transport) {
            case WebSocketTransport ws -> ws.connect(uri, request);
            case SSETransport sse -> sse.connect(uri, request);
            case StreamTransport stream -> stream.connect(uri, request);
            case LongPollingTransport lp -> lp.connect(uri, request);
            default -> throw new IllegalStateException("Unknown transport: " + transport);
        }

        status = STATUS.OPEN;

        // Register reconnection logic
        if (options.reconnect()) {
            registerReconnection(transport, transports, index);
        }
    }

    private void registerReconnection(Transport transport, List<Request.TRANSPORT> transports, int index) {
        transport.registerFunction(new FunctionBinding(Event.CLOSE.name(), (Function<Object>) reason -> {
            if (status == STATUS.CLOSE) {
                return;
            }

            var maxAttempts = options.reconnectAttempts();
            var attempt = new AtomicInteger(0);

            Thread.ofVirtual().name("wasync-reconnect").start(() -> {
                while (status != STATUS.CLOSE
                        && (maxAttempts == -1 || attempt.get() < maxAttempts)) {
                    try {
                        Thread.sleep(options.reconnectTimeoutInMilliseconds());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    logger.info("Reconnection attempt {}", attempt.incrementAndGet());
                    connectWithFallback(transports, index);

                    if (activeTransport.status() == STATUS.OPEN) {
                        status = STATUS.REOPENED;
                        dispatchReopened();
                        return;
                    }
                }
            });
        }));
    }

    @SuppressWarnings("unchecked")
    private void dispatchError(Throwable error) {
        for (var binding : functions) {
            if (binding.functionName().equalsIgnoreCase(Event.ERROR.name())) {
                ((Function<Object>) binding.function()).on(error);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchReopened() {
        for (var binding : functions) {
            if (binding.functionName().equalsIgnoreCase(Event.REOPENED.name())) {
                ((Function<Object>) binding.function()).on("");
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CompletableFuture<Void> fire(Object data) {
        if (activeTransport == null || status == STATUS.CLOSE) {
            return CompletableFuture.failedFuture(new IllegalStateException("Socket not open"));
        }

        // Run through encoders
        Object encoded = data;
        for (Encoder encoder : request.encoders()) {
            try {
                var result = encoder.encode(encoded);
                if (result != null) {
                    encoded = result;
                }
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        var uri = buildUri(request);

        if (encoded instanceof byte[] bytes && activeTransport instanceof WebSocketTransport ws) {
            return ws.sendBinary(bytes);
        }

        var text = encoded.toString();
        return switch (activeTransport) {
            case WebSocketTransport ws -> ws.sendText(text);
            case StreamTransport stream -> stream.sendText(text, uri, request);
            case LongPollingTransport lp -> lp.sendText(text, uri, request);
            default -> CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Cannot fire on " + activeTransport.name()));
        };
    }

    @Override
    public void close() {
        status = STATUS.CLOSE;
        if (activeTransport != null) {
            activeTransport.close();
        }
        if (ownHttpClient && httpClient != null) {
            httpClient.close();
        }
    }

    @Override
    public STATUS status() {
        return status;
    }

    private void initHttpClient() {
        if (options.httpClient() != null) {
            this.httpClient = options.httpClient();
            this.ownHttpClient = !options.httpClientShared();
        } else {
            this.httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            this.ownHttpClient = true;
        }
    }

    private Transport createTransport(Request.TRANSPORT type) {
        if (options.transport() != null && options.transport().name() == type) {
            return options.transport();
        }
        return switch (type) {
            case WEBSOCKET -> new WebSocketTransport(httpClient, options);
            case SSE -> new SSETransport(httpClient, options);
            case STREAMING -> new StreamTransport(httpClient, options);
            case LONG_POLLING -> new LongPollingTransport(httpClient, options);
        };
    }

    protected URI buildUri(Request request) {
        var uriStr = request.uri();
        var qs = request.queryString();
        if (!qs.isEmpty()) {
            var sb = new StringBuilder(uriStr);
            sb.append(uriStr.contains("?") ? '&' : '?');
            var first = true;
            for (var entry : qs.entrySet()) {
                for (var value : entry.getValue()) {
                    if (!first) {
                        sb.append('&');
                    }
                    sb.append(entry.getKey()).append('=').append(value);
                    first = false;
                }
            }
            uriStr = sb.toString();
        }
        return URI.create(uriStr);
    }
}
