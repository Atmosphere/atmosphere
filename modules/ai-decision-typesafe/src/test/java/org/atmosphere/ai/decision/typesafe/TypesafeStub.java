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
package org.atmosphere.ai.decision.typesafe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A JDK {@link HttpServer} on a loopback port that replays canned responses for
 * {@code POST /v1/systemone} and {@code GET /v1/models} and records what it was
 * sent. Responses are queued per path; the last one queued repeats once the
 * queue is down to one.
 */
final class TypesafeStub implements AutoCloseable {

    /** One canned response; {@code delayMillis} is slept before answering. */
    record Reply(int status, Map<String, String> headers, byte[] body, long delayMillis) {

        static Reply json(int status, String body) {
            return new Reply(status, Map.of(), body.getBytes(StandardCharsets.UTF_8), 0);
        }

        Reply withHeader(String name, String value) {
            var copy = new LinkedHashMap<>(headers);
            copy.put(name, value);
            return new Reply(status, Map.copyOf(copy), body, delayMillis);
        }

        Reply delayed(long millis) {
            return new Reply(status, headers, body, millis);
        }
    }

    /** One recorded request. */
    record Recorded(String method, String path, String authorization, String contentType, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedDeque<Reply> decide = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Reply> models = new ConcurrentLinkedDeque<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> beforeDecideReply = new AtomicReference<>(() -> { });

    TypesafeStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/systemone", exchange -> handle(exchange, decide, true));
        server.createContext("/v1/models", exchange -> handle(exchange, models, false));
        server.setExecutor(executor);
        server.start();
        models.add(Reply.json(200, fixture("models.json")));
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    TypesafeStub onDecide(Reply... replies) {
        decide.clear();
        decide.addAll(List.of(replies));
        return this;
    }

    TypesafeStub onModels(Reply... replies) {
        models.clear();
        models.addAll(List.of(replies));
        return this;
    }

    /** Runs on the server thread before each systemone reply (e.g. to block on a latch). */
    TypesafeStub beforeDecideReply(Runnable hook) {
        beforeDecideReply.set(hook);
        return this;
    }

    List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    long hits(String path) {
        return recorded.stream().filter(r -> r.path().equals(path)).count();
    }

    private void handle(HttpExchange exchange, ConcurrentLinkedDeque<Reply> queue, boolean isDecide)
            throws IOException {
        try (exchange; InputStream in = exchange.getRequestBody()) {
            var body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            var reply = queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
            if (reply == null) {
                reply = Reply.json(500, "{\"detail\":\"stub has no reply queued\"}");
            }
            if (isDecide) {
                beforeDecideReply.get().run();
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-typesafe-request-id", "req_stub_" + recorded.size());
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) {
                exchange.getResponseBody().write(reply.body());
            }
        }
    }

    static String fixture(String name) {
        try (var in = TypesafeStub.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
