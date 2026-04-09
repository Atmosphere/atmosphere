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
package org.atmosphere.channels.discord;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.atmosphere.channels.ChannelHttpClient;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.IncomingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Discord Gateway WebSocket client for receiving real-time message events.
 * <p>
 * Connects to {@code wss://gateway.discord.gg}, authenticates with IDENTIFY,
 * maintains heartbeat, and emits {@link IncomingMessage} on MESSAGE_CREATE.
 * Automatically reconnects with exponential backoff on disconnection.
 * <p>
 * Ported from <a href="https://github.com/dravr-ai/dravr-canot">Canot</a>'s
 * Rust Discord Gateway implementation.
 */
public class DiscordGateway implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(DiscordGateway.class);
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    // Opcodes
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    // Intents: GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
    private static final long GATEWAY_INTENTS = (1L << 0) | (1L << 9) | (1L << 12) | (1L << 15);

    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final String botToken;
    private final ObjectMapper objectMapper;
    private final Consumer<IncomingMessage> messageHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("discord-gateway").unstarted(r));

    private final AtomicLong lastSequence = new AtomicLong(-1);
    private final AtomicReference<String> botUserId = new AtomicReference<>();
    private volatile StringBuilder messageBuffer = new StringBuilder();

    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicBoolean heartbeatAckPending = new AtomicBoolean();
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean running;

    public DiscordGateway(String botToken, ObjectMapper objectMapper,
                          Consumer<IncomingMessage> messageHandler) {
        this.botToken = botToken;
        this.objectMapper = objectMapper;
        this.messageHandler = messageHandler;
    }

    /**
     * Start the Gateway connection. Runs in the background with auto-reconnect.
     */
    public void start() {
        running = true;
        connect(0);
    }

    /**
     * Stop the Gateway connection.
     */
    public void stop() {
        running = false;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void connect(int attempt) {
        if (!running || attempt > MAX_RECONNECT_ATTEMPTS) {
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                log.error("Discord Gateway: max reconnect attempts exhausted");
            }
            reconnecting.set(false);
            return;
        }

        if (attempt > 0) {
            long delaySecs = Math.min(1L << attempt, 60);
            log.warn("Discord Gateway: reconnecting in {}s (attempt {})", delaySecs, attempt);
            scheduler.schedule(() -> doConnect(attempt), delaySecs, TimeUnit.SECONDS);
        } else {
            doConnect(attempt);
        }
    }

    private void doConnect(int attempt) {
        try {
            heartbeatAckPending.set(false);
            messageBuffer = new StringBuilder();

            ChannelHttpClient.get().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(GATEWAY_URL), this)
                    .thenAccept(ws -> {
                        var old = this.webSocket;
                        this.webSocket = ws;
                        if (old != null) {
                            try { old.sendClose(WebSocket.NORMAL_CLOSURE, "replaced"); }
                            catch (Exception e) { log.trace("Error closing previous WebSocket", e); }
                        }
                        reconnecting.set(false);
                        log.info("Discord Gateway: connected");
                    })
                    .exceptionally(e -> {
                        log.error("Discord Gateway: connection failed: {}", e.getMessage());
                        // Don't reset reconnecting — connect() will retry or exhaust attempts
                        connect(attempt + 1);
                        return null;
                    });
        } catch (Exception e) {
            log.error("Discord Gateway: failed to initiate connection: {}", e.getMessage());
            connect(attempt + 1);
        }
    }

    // ---- WebSocket.Listener ----

    @Override
    public void onOpen(WebSocket ws) {
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        messageBuffer.append(data);

        if (last) {
            handleMessage(ws, messageBuffer.toString());
            messageBuffer.setLength(0);
        }

        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        log.warn("Discord Gateway: closed (code={}, reason={})", statusCode, reason);
        if (running && reconnecting.compareAndSet(false, true)) {
            // Don't reset reconnecting here — connect() is async. The flag is
            // reset in doConnect's thenAccept/exceptionally handlers.
            connect(1);
        }
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        log.error("Discord Gateway: error: {}", error.getMessage());
        if (running && reconnecting.compareAndSet(false, true)) {
            connect(1);
        }
    }

    // ---- Message handling ----

    private void handleMessage(WebSocket ws, String text) {
        try {
            JsonNode payload = objectMapper.readTree(text);
            int op = payload.path("op").intValue();

            // Track sequence
            if (payload.has("s") && !payload.get("s").isNull()) {
                lastSequence.set(payload.get("s").longValue());
            }

            switch (op) {
                case OP_HELLO -> handleHello(ws, payload);
                case OP_DISPATCH -> handleDispatch(payload);
                case OP_HEARTBEAT_ACK -> heartbeatAckPending.set(false);
                case OP_HEARTBEAT -> sendHeartbeat(ws);
                case OP_RECONNECT -> {
                    log.info("Discord Gateway: server requested reconnect");
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                }
                case OP_INVALID_SESSION -> {
                    log.warn("Discord Gateway: invalid session");
                    scheduler.schedule(() -> connect(1), 3, TimeUnit.SECONDS);
                }
                default -> log.debug("Discord Gateway: unhandled opcode {}", op);
            }
        } catch (Exception e) {
            log.warn("Discord Gateway: failed to handle message: {}", e.getMessage());
        }
    }

    private void handleHello(WebSocket ws, JsonNode payload) {
        long intervalMs = payload.path("d").path("heartbeat_interval").longValue(41250);
        log.debug("Discord Gateway: HELLO (heartbeat={}ms)", intervalMs);

        // Start heartbeat
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(
                () -> {
                    if (!heartbeatAckPending.compareAndSet(false, true)) {
                        log.warn("Discord Gateway: missed heartbeat ACK, reconnecting");
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "missed heartbeat");
                        return;
                    }
                    sendHeartbeat(ws);
                },
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // Send IDENTIFY
        String identify = """
                {"op":%d,"d":{"token":"%s","intents":%d,"properties":{"os":"linux","browser":"atmosphere","device":"atmosphere"}}}"""
                .formatted(OP_IDENTIFY, botToken, GATEWAY_INTENTS);
        ws.sendText(identify, true);
    }

    private void handleDispatch(JsonNode payload) {
        String eventName = payload.path("t").stringValue("");

        switch (eventName) {
            case "READY" -> {
                String userId = payload.path("d").path("user").path("id").stringValue(null);
                botUserId.set(userId);
                log.info("Discord Gateway: READY (bot={})", userId);
            }
            case "MESSAGE_CREATE" -> {
                JsonNode data = payload.get("d");
                if (data != null) {
                    parseMessageCreate(data).ifPresent(messageHandler);
                }
            }
            default -> log.debug("Discord Gateway: unhandled event {}", eventName);
        }
    }

    private Optional<IncomingMessage> parseMessageCreate(JsonNode data) {
        JsonNode author = data.get("author");
        if (author == null) return Optional.empty();

        // Skip bot messages (including ourselves)
        if (author.path("bot").booleanValue()) return Optional.empty();

        String authorId = author.path("id").stringValue("");
        String myId = botUserId.get();
        if (myId != null && myId.equals(authorId)) return Optional.empty();

        String content = data.path("content").stringValue("");
        if (content.isEmpty()) return Optional.empty();

        String username = author.path("username").stringValue(null);
        String channelId = data.path("channel_id").stringValue("");
        String messageId = data.path("id").stringValue("");

        return Optional.of(new IncomingMessage(
                ChannelType.DISCORD,
                authorId,
                Optional.ofNullable(username),
                content,
                channelId,
                messageId,
                Instant.now()
        ));
    }

    private void sendHeartbeat(WebSocket ws) {
        long seq = lastSequence.get();
        String heartbeat = seq < 0
                ? """
                {"op":%d,"d":null}""".formatted(OP_HEARTBEAT)
                : """
                {"op":%d,"d":%d}""".formatted(OP_HEARTBEAT, seq);
        ws.sendText(heartbeat, true);
    }
}
