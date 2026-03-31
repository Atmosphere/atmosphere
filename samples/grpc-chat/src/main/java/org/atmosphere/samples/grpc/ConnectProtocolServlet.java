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
package org.atmosphere.samples.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.stub.StreamObserver;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.grpc.GrpcProcessor;
import org.atmosphere.grpc.proto.AtmosphereMessage;
import org.atmosphere.grpc.proto.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements the <a href="https://connectrpc.com/docs/protocol">Connect protocol</a> over HTTP,
 * bridging browser clients to the Atmosphere gRPC transport.
 *
 * <p>Supports two RPCs:
 * <ul>
 *   <li>{@code Subscribe} — server-streaming: client POSTs a SUBSCRIBE message, receives a
 *       stream of enveloped messages</li>
 *   <li>{@code Send} — unary: client POSTs a MESSAGE, receives an ACK</li>
 * </ul>
 *
 * <p>Both binary ({@code application/proto}) and JSON ({@code application/json}) content types
 * are supported. The response format matches the request format.
 *
 * <p>Mount at {@code /org.atmosphere.grpc.AtmosphereService/*} in a servlet container.
 */
public class ConnectProtocolServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(ConnectProtocolServlet.class);

    private static final String CONTENT_TYPE_PROTO = "application/proto";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_CONNECT_PROTO = "application/connect+proto";
    private static final String CONTENT_TYPE_CONNECT_JSON = "application/connect+json";

    private final GrpcProcessor processor;

    public ConnectProtocolServlet(GrpcProcessor processor) {
        this.processor = processor;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        addCorsHeaders(resp);

        var path = req.getPathInfo();
        if (path == null) {
            sendConnectError(resp, 404, "not_found", "Missing RPC path", isJson(req));
            return;
        }

        switch (path) {
            case "/Subscribe" -> handleSubscribe(req, resp);
            case "/Send" -> handleSend(req, resp);
            default -> sendConnectError(resp, 404, "unimplemented",
                    "Unknown RPC: " + path, isJson(req));
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        addCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void handleSubscribe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var message = parseRequest(req);
        if (message == null) {
            sendConnectError(resp, 400, "invalid_argument", "Cannot parse request body", isJson(req));
            return;
        }

        boolean json = isJson(req);

        // Start async context — response stays open for streaming
        var async = req.startAsync();
        async.setTimeout(0);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(json ? CONTENT_TYPE_CONNECT_JSON : CONTENT_TYPE_CONNECT_PROTO);
        resp.setHeader("Connect-Content-Encoding", "identity");

        var outputStream = resp.getOutputStream();

        // StreamObserver that writes Connect-enveloped messages to the HTTP response
        var observer = new ConnectStreamObserver(outputStream, async, json);

        var channel = processor.open(observer);

        // Clean up channel when the async context completes (client disconnect, timeout, etc.)
        async.addListener(new jakarta.servlet.AsyncListener() {
            @Override
            public void onComplete(jakarta.servlet.AsyncEvent event) {
                processor.close(channel);
            }

            @Override
            public void onTimeout(jakarta.servlet.AsyncEvent event) {
                processor.close(channel);
            }

            @Override
            public void onError(jakarta.servlet.AsyncEvent event) {
                processor.close(channel);
            }

            @Override
            public void onStartAsync(jakarta.servlet.AsyncEvent event) {
                // no-op
            }
        });

        var subscribeMsg = message.toBuilder()
                .setType(MessageType.SUBSCRIBE)
                .setTrackingId(channel.uuid())
                .build();

        try {
            processor.onMessage(channel, subscribeMsg);
            logger.debug("Connect client {} subscribed to topic {}", channel.uuid(), message.getTopic());
        } catch (Exception e) {
            logger.error("Error processing Connect subscribe for channel {}", channel.uuid(), e);
            observer.onError(e);
        }
    }

    private void handleSend(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var message = parseRequest(req);
        if (message == null) {
            sendConnectError(resp, 400, "invalid_argument", "Cannot parse request body", isJson(req));
            return;
        }

        boolean json = isJson(req);
        var trackingId = message.getTrackingId();
        var channel = processor.getChannel(trackingId);

        if (channel == null) {
            sendConnectError(resp, 404, "not_found",
                    "No channel found for tracking_id: " + trackingId, json);
            return;
        }

        var sendMsg = message.toBuilder()
                .setType(MessageType.MESSAGE)
                .build();

        try {
            processor.onMessage(channel, sendMsg);

            var ack = AtmosphereMessage.newBuilder()
                    .setType(MessageType.ACK)
                    .setTopic(message.getTopic())
                    .setTrackingId(trackingId)
                    .build();

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(json ? CONTENT_TYPE_JSON : CONTENT_TYPE_PROTO);
            addCorsHeaders(resp);

            if (json) {
                resp.getOutputStream().write(
                        JsonFormat.printer().print(ack).getBytes(StandardCharsets.UTF_8));
            } else {
                ack.writeTo(resp.getOutputStream());
            }
            resp.getOutputStream().flush();
        } catch (Exception e) {
            logger.error("Error processing Connect send for channel {}", trackingId, e);
            sendConnectError(resp, 500, "internal", "Internal server error", json);
        }
    }

    private AtmosphereMessage parseRequest(HttpServletRequest req) throws IOException {
        var rawBody = req.getInputStream().readAllBytes();

        // Connect streaming content types (application/connect+json, application/connect+proto)
        // wrap the body in a 5-byte envelope: flags(1) + length(4 big-endian).
        // Strip the envelope to get the raw message bytes.
        if (isStreamingContentType(req) && rawBody.length >= 5) {
            int payloadLen = ((rawBody[1] & 0xFF) << 24)
                    | ((rawBody[2] & 0xFF) << 16)
                    | ((rawBody[3] & 0xFF) << 8)
                    | (rawBody[4] & 0xFF);
            if (rawBody.length >= 5 + payloadLen) {
                var payload = new byte[payloadLen];
                System.arraycopy(rawBody, 5, payload, 0, payloadLen);
                rawBody = payload;
            }
        }

        if (isJson(req)) {
            var jsonBody = new String(rawBody, StandardCharsets.UTF_8);
            try {
                var builder = AtmosphereMessage.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(jsonBody, builder);
                return builder.build();
            } catch (InvalidProtocolBufferException e) {
                logger.warn("Failed to parse JSON request: {}", e.getMessage());
                return null;
            }
        } else {
            try {
                return AtmosphereMessage.parseFrom(rawBody);
            } catch (InvalidProtocolBufferException e) {
                logger.warn("Failed to parse proto request: {}", e.getMessage());
                return null;
            }
        }
    }

    private boolean isStreamingContentType(HttpServletRequest req) {
        var ct = req.getContentType();
        return ct != null && ct.startsWith("application/connect+");
    }

    private boolean isJson(HttpServletRequest req) {
        var ct = req.getContentType();
        return ct != null && ct.contains("json");
    }

    private void sendConnectError(HttpServletResponse resp, int httpStatus, String code,
                                  String message, boolean json) throws IOException {
        resp.setStatus(httpStatus);
        resp.setContentType(json ? CONTENT_TYPE_JSON : CONTENT_TYPE_PROTO);
        addCorsHeaders(resp);
        var errorJson = "{\"code\":\"" + escapeJson(code) + "\",\"message\":\""
                + escapeJson(message) + "\"}";
        resp.getOutputStream().write(errorJson.getBytes(StandardCharsets.UTF_8));
        resp.getOutputStream().flush();
    }

    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers",
                "Content-Type, Connect-Protocol-Version, Connect-Timeout-Ms, Connect-Accept-Encoding");
        resp.setHeader("Access-Control-Expose-Headers",
                "Connect-Content-Encoding, Connect-Protocol-Version");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        var sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * StreamObserver that writes Connect protocol enveloped messages to an HTTP output stream.
     * Each envelope: {@code flags(1 byte) | length(4 bytes big-endian) | data}.
     */
    private static class ConnectStreamObserver implements StreamObserver<AtmosphereMessage> {

        private final OutputStream out;
        private final AsyncContext async;
        private final boolean json;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean completed;

        ConnectStreamObserver(OutputStream out, AsyncContext async, boolean json) {
            this.out = out;
            this.async = async;
            this.json = json;
        }

        @Override
        public void onNext(AtmosphereMessage message) {
            if (completed) {
                return;
            }
            lock.lock();
            try {
                byte[] data;
                if (json) {
                    data = JsonFormat.printer().print(message).getBytes(StandardCharsets.UTF_8);
                } else {
                    data = message.toByteArray();
                }
                writeEnvelope((byte) 0, data);
                out.flush();
            } catch (InvalidProtocolBufferException e) {
                logger.warn("Failed to serialize message to JSON", e);
            } catch (IOException e) {
                logger.trace("Write failed on Connect stream (client likely disconnected)", e);
                completeQuietly();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (completed) {
                return;
            }
            lock.lock();
            try {
                logger.error("Stream observer error", t);
                var errorJson = "{\"error\":{\"code\":\"internal\",\"message\":\""
                        + escapeJson("Internal server error") + "\"}}";
                writeEnvelope((byte) 2, errorJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                logger.trace("Failed to write error envelope", e);
            } finally {
                lock.unlock();
                completeQuietly();
            }
        }

        @Override
        public void onCompleted() {
            if (completed) {
                return;
            }
            lock.lock();
            try {
                // End-stream envelope with empty trailers
                writeEnvelope((byte) 2, "{}".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                logger.trace("Failed to write end-of-stream envelope", e);
            } finally {
                lock.unlock();
                completeQuietly();
            }
        }

        private void writeEnvelope(byte flags, byte[] data) throws IOException {
            int length = data.length;
            out.write(flags);
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
            out.write(data);
        }

        private void completeQuietly() {
            completed = true;
            try {
                async.complete();
            } catch (Exception e) {
                logger.trace("Error completing async context", e);
            }
        }
    }
}
