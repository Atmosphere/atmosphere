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
package org.atmosphere.benchmarks.load;

import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.impl.AtmosphereClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standalone streaming load test using the wAsync Atmosphere client.
 * Connects N concurrent WebSocket clients to an Atmosphere server,
 * sends M messages per client, and reports latency/throughput metrics.
 *
 * <p>This is NOT a JMH benchmark. Run it as a regular Java main class
 * against a running Atmosphere server instance.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * java -cp benchmarks.jar org.atmosphere.benchmarks.load.StreamingLoadTest \
 *     --url ws://localhost:8080/atmosphere/chat \
 *     --clients 100 \
 *     --messages 10
 * }</pre>
 */
public final class StreamingLoadTest {

    private static final String DEFAULT_URL = "ws://localhost:8080/atmosphere/chat";
    private static final int DEFAULT_CLIENTS = 100;
    private static final int DEFAULT_MESSAGES = 10;
    private static final long CONNECT_TIMEOUT_SECONDS = 30;
    private static final long MESSAGE_TIMEOUT_SECONDS = 120;

    private StreamingLoadTest() {
        // utility class
    }

    public static void main(String[] args) throws Exception {
        var url = DEFAULT_URL;
        var clientCount = DEFAULT_CLIENTS;
        var messageCount = DEFAULT_MESSAGES;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--clients" -> clientCount = Integer.parseInt(args[++i]);
                case "--messages" -> messageCount = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        System.out.println("=== Atmosphere Streaming Load Test ===");
        System.out.printf("URL:      %s%n", url);
        System.out.printf("Clients:  %d%n", clientCount);
        System.out.printf("Messages: %d per client%n", messageCount);
        System.out.println();

        runLoadTest(url, clientCount, messageCount);
    }

    private static void runLoadTest(String url, int clientCount, int messageCount) throws Exception {
        var connectLatencies = new ConcurrentLinkedQueue<Long>();
        var messageLatencies = new ConcurrentLinkedQueue<Long>();
        var errorCount = new LongAdder();
        var messagesReceived = new LongAdder();
        var connectLatch = new CountDownLatch(clientCount);

        var sockets = new ArrayList<Socket>(clientCount);
        var testStartNanos = System.nanoTime();

        // Phase 1: Connect all clients
        System.out.println("Connecting clients...");
        for (int i = 0; i < clientCount; i++) {
            final int clientId = i;
            try {
                var client = AtmosphereClient.newClient();
                var options = client.newOptionsBuilder()
                        .reconnect(false)
                        .build();

                var request = client.newRequestBuilder()
                        .uri(url)
                        .transport(Request.TRANSPORT.WEBSOCKET)
                        .enableProtocol(false)
                        .build();

                var socket = client.create(options);
                var connectStart = System.nanoTime();

                socket.on(Event.OPEN, (Function<Object>) o -> {
                            var elapsed = System.nanoTime() - connectStart;
                            connectLatencies.add(elapsed);
                            connectLatch.countDown();
                        })
                        .on(Event.MESSAGE, (Function<Object>) m -> {
                            var msg = m.toString().strip();
                            if (msg.startsWith("{")) {
                                messagesReceived.increment();
                            }
                        })
                        .on(Event.ERROR, (Function<Object>) e -> errorCount.increment())
                        .open(request);

                sockets.add(socket);
            } catch (Exception e) {
                System.err.printf("Client %d failed to create: %s%n", clientId, e.getMessage());
                errorCount.increment();
                connectLatch.countDown();
            }
        }

        if (!connectLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            System.err.printf("WARNING: Only %d/%d clients connected within %ds%n",
                    clientCount - (int) connectLatch.getCount(), clientCount, CONNECT_TIMEOUT_SECONDS);
        }

        var connectedCount = connectLatencies.size();
        System.out.printf("Connected: %d/%d clients%n%n", connectedCount, clientCount);

        if (connectedCount == 0) {
            System.err.println("No clients connected. Is the server running?");
            cleanup(sockets);
            return;
        }

        // Phase 2: Send messages from each client
        System.out.println("Sending messages...");
        var sendLatch = new CountDownLatch(connectedCount * messageCount);
        var messageStartNanos = System.nanoTime();

        for (int i = 0; i < sockets.size(); i++) {
            final int clientId = i;
            var socket = sockets.get(i);
            if (socket.status() != Socket.STATUS.OPEN) {
                // Skip sockets that didn't connect
                for (int m = 0; m < messageCount; m++) {
                    sendLatch.countDown();
                }
                continue;
            }
            Thread.startVirtualThread(() -> {
                for (int m = 0; m < messageCount; m++) {
                    var sendStart = System.nanoTime();
                    try {
                        var json = String.format(
                                "{\"author\":\"client-%d\",\"message\":\"hello-%d\"}", clientId, m);
                        socket.fire(json).whenComplete((v, ex) -> {
                            if (ex != null) {
                                errorCount.increment();
                            } else {
                                messageLatencies.add(System.nanoTime() - sendStart);
                            }
                            sendLatch.countDown();
                        });
                    } catch (Exception e) {
                        errorCount.increment();
                        sendLatch.countDown();
                    }
                }
            });
        }

        if (!sendLatch.await(MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            System.err.printf("WARNING: Not all sends completed within %ds%n", MESSAGE_TIMEOUT_SECONDS);
        }

        var totalElapsedNanos = System.nanoTime() - testStartNanos;
        var sendElapsedNanos = System.nanoTime() - messageStartNanos;

        // Phase 3: Report metrics
        printReport(connectedCount, clientCount,
                connectLatencies, messageLatencies,
                messagesReceived.sum(), errorCount.sum(),
                totalElapsedNanos, sendElapsedNanos);

        // Cleanup
        cleanup(sockets);
    }

    private static void printReport(int connected, int total,
                                    ConcurrentLinkedQueue<Long> connectLatencies,
                                    ConcurrentLinkedQueue<Long> messageLatencies,
                                    long received, long errors,
                                    long totalNanos, long sendNanos) {
        System.out.println();
        System.out.println("=== Results ===");
        System.out.printf("Clients connected:   %d / %d%n", connected, total);
        System.out.printf("Messages sent:       %d%n", messageLatencies.size());
        System.out.printf("Messages received:   %d%n", received);
        System.out.printf("Errors:              %d%n", errors);
        System.out.println();

        // Connect latency
        if (!connectLatencies.isEmpty()) {
            var sorted = sortedNanos(connectLatencies);
            System.out.println("Connect latency:");
            System.out.printf("  p50:  %s%n", formatNanos(percentile(sorted, 50)));
            System.out.printf("  p95:  %s%n", formatNanos(percentile(sorted, 95)));
            System.out.printf("  p99:  %s%n", formatNanos(percentile(sorted, 99)));
            System.out.println();
        }

        // Message send latency
        if (!messageLatencies.isEmpty()) {
            var sorted = sortedNanos(messageLatencies);
            System.out.println("Send latency:");
            System.out.printf("  p50:  %s%n", formatNanos(percentile(sorted, 50)));
            System.out.printf("  p95:  %s%n", formatNanos(percentile(sorted, 95)));
            System.out.printf("  p99:  %s%n", formatNanos(percentile(sorted, 99)));
            System.out.println();
        }

        // Throughput
        var sendSeconds = sendNanos / 1_000_000_000.0;
        var totalSeconds = totalNanos / 1_000_000_000.0;
        if (sendSeconds > 0 && !messageLatencies.isEmpty()) {
            System.out.printf("Send throughput:     %.1f messages/sec%n",
                    messageLatencies.size() / sendSeconds);
        }
        System.out.printf("Total elapsed:       %.2f seconds%n", totalSeconds);
    }

    private static List<Long> sortedNanos(ConcurrentLinkedQueue<Long> queue) {
        var list = new ArrayList<>(queue);
        Collections.sort(list);
        return list;
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    private static String formatNanos(long nanos) {
        if (nanos < 1_000) {
            return nanos + " ns";
        } else if (nanos < 1_000_000) {
            return String.format("%.1f us", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000L) {
            return String.format("%.1f ms", nanos / 1_000_000.0);
        } else {
            return String.format("%.2f s", nanos / 1_000_000_000.0);
        }
    }

    private static void cleanup(List<Socket> sockets) {
        for (var socket : sockets) {
            try {
                socket.close();
            } catch (Exception e) {
                // best effort
            }
        }
    }

    private static void printUsage() {
        System.err.println("Usage: StreamingLoadTest [options]");
        System.err.println("  --url <url>         Server URL (default: " + DEFAULT_URL + ")");
        System.err.println("  --clients <n>       Number of clients (default: " + DEFAULT_CLIENTS + ")");
        System.err.println("  --messages <n>      Messages per client (default: " + DEFAULT_MESSAGES + ")");
    }
}
