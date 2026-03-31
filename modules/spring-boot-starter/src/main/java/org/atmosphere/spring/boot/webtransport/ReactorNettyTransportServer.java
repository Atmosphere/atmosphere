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
package org.atmosphere.spring.boot.webtransport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3Settings;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.spring.boot.AtmosphereProperties.WebTransportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Spring-managed HTTP/3 server that bridges HTTP/3 requests and WebTransport
 * sessions into the Atmosphere framework. Uses raw Netty HTTP/3 codec
 * (not Reactor Netty's HttpServer) because WebTransport requires:
 * <ul>
 *   <li>{@code ENABLE_CONNECT_PROTOCOL} in HTTP/3 SETTINGS (RFC 9220)</li>
 *   <li>Access to the {@code :protocol} pseudo-header for extended CONNECT</li>
 * </ul>
 * <p>Neither is exposed by Reactor Netty's high-level API.</p>
 *
 * <p>The server listens on a separate UDP port and runs alongside the
 * servlet container. Regular HTTP/3 requests are bridged to
 * {@link AtmosphereFramework#doCometSupport}, while WebTransport extended
 * CONNECT requests create bidirectional stream sessions.</p>
 */
public class ReactorNettyTransportServer {

    private static final Logger logger = LoggerFactory.getLogger(ReactorNettyTransportServer.class);

    private final AtmosphereFramework framework;
    private final WebTransportProperties properties;
    private Channel serverChannel;
    private IoEventLoopGroup group;
    private String certificateHashBase64;

    public ReactorNettyTransportServer(AtmosphereFramework framework,
                                        WebTransportProperties properties) {
        this.framework = framework;
        this.properties = properties;
    }

    /**
     * Start the HTTP/3 + WebTransport server on the configured UDP port.
     */
    public void start() {
        try {
            var sslContext = buildQuicSslContext();

            // HTTP/3 settings with ENABLE_CONNECT_PROTOCOL for WebTransport (RFC 9220)
            // WebTransport over HTTP/3 requires these settings in the SETTINGS frame:
            // - ENABLE_CONNECT_PROTOCOL (0x08) = 1  (RFC 9220)
            // - H3_DATAGRAM (0x33) = 1              (RFC 9297)
            // - WEBTRANSPORT_MAX_SESSIONS (0xc671706a) = 1 (draft-ietf-webtrans-http3)
            // Use a permissive validator for non-standard WebTransport settings
            var settings = new Http3Settings((id, value) -> true)
                    .enableConnectProtocol(true)       // 0x08 = 1 (RFC 9220)
                    .enableH3Datagram(true)            // 0x33 = 1 (RFC 9297)
                    .qpackMaxTableCapacity(0)          // Disable QPACK dynamic table
                    .qpackBlockedStreams(0);
            settings.put(0x2b603742L, 1L);   // SETTINGS_WEBTRANS_DRAFT00 (draft-02)
            settings.put(0xc671706aL, 256L); // SETTINGS_WEBTRANS_MAX_SESSIONS (draft-07)
            var settingsFrame = new DefaultHttp3SettingsFrame(settings);
            logger.info("HTTP/3 SETTINGS frame: {}", settingsFrame);

            var wtSessions = new java.util.concurrent.ConcurrentHashMap<Channel, WebTransportConnectionState>();

            var codec = Http3.newQuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(30, TimeUnit.SECONDS)
                    .initialMaxData(10_000_000)
                    .initialMaxStreamDataBidirectionalLocal(1_000_000)
                    .initialMaxStreamDataBidirectionalRemote(1_000_000)
                    .initialMaxStreamsBidirectional(100)
                    .initialMaxStreamDataUnidirectional(1_000_000)
                    .initialMaxStreamsUnidirectional(100)
                    .datagram(65536, 65536)
                    .tokenHandler(io.netty.handler.codec.quic.InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            logger.info("QUIC connection established: {}", ch);
                            try {
                                var connHandler = new Http3ServerConnectionHandler(
                                        new ChannelInitializer<QuicStreamChannel>() {
                                            @Override
                                            protected void initChannel(QuicStreamChannel streamCh) {
                                                logger.info("HTTP/3 request stream opened: {}", streamCh);
                                                streamCh.pipeline().addLast(
                                                        new AtmosphereHttp3Handler(framework, wtSessions));
                                            }
                                        },
                                        new io.netty.channel.ChannelInboundHandlerAdapter(),
                                        null, settingsFrame, true,
                                        (id, value) -> true);
                                // Intercept child streams BEFORE Http3ServerConnectionHandler
                                // to catch WebTransport bidi streams (frame type 0x41)
                                ch.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext connCtx, Object msg) throws Exception {
                                        if (msg instanceof QuicStreamChannel streamCh
                                                && streamCh.type() == io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL
                                                && wtSessions.containsKey(ch)) {
                                            // This is a WT bidi data stream — handle directly, bypass HTTP/3 codec
                                            streamCh.pipeline().addLast(
                                                    new WebTransportBidiStreamHandler(framework, wtSessions));
                                            // Register and start reading
                                            streamCh.config().setAutoRead(true);
                                            ch.eventLoop().register(streamCh).addListener(f -> {
                                                if (f.isSuccess()) {
                                                    streamCh.read();
                                                    logger.info("WT bidi stream registered OK, reading: {}", streamCh);
                                                } else {
                                                    logger.error("WT bidi stream register failed", f.cause());
                                                }
                                            });
                                            logger.info("WT bidi stream intercepted: {}", streamCh);
                                            return; // DON'T pass to Http3ServerConnectionHandler
                                        }
                                        super.channelRead(connCtx, msg);
                                    }
                                });
                                ch.pipeline().addLast(connHandler);
                                logger.info("Http3ServerConnectionHandler installed, pipeline: {}",
                                        ch.pipeline().names());
                            } catch (Exception e) {
                                logger.error("Failed to install Http3ServerConnectionHandler", e);
                            }
                        }
                    })
                    .build();

            group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            serverChannel = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(properties.getHost(), properties.getPort()))
                    .sync()
                    .channel();

            logger.info("Atmosphere HTTP/3 + WebTransport server started on {}:{} (ENABLE_CONNECT_PROTOCOL=true)",
                    properties.getHost(), properties.getPort());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start HTTP/3 + WebTransport server", e);
        }
    }

    /**
     * Stop the server and release resources.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        org.atmosphere.cpr.WebTransportProcessorFactory.getDefault().destroy();
        logger.info("Atmosphere HTTP/3 + WebTransport server stopped");
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    public int port() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return -1;
    }

    /**
     * Return the base64-encoded SHA-256 hash of the server certificate,
     * for use with the browser's {@code serverCertificateHashes} option.
     */
    public String certificateHash() {
        return certificateHashBase64;
    }

    // ── SSL ──────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation") // SelfSignedCertificate deprecated in Netty 4.2
    private QuicSslContext buildQuicSslContext() {
        var sslProps = properties.getSsl();
        if (sslProps.getCertificate() != null && sslProps.getPrivateKey() != null) {
            var certFile = Path.of(sslProps.getCertificate()).toFile();
            var keyFile = Path.of(sslProps.getPrivateKey()).toFile();
            return QuicSslContextBuilder.forServer(keyFile, sslProps.getPrivateKeyPassword(), certFile)
                    .applicationProtocols(Http3.supportedApplicationProtocols())
                    .build();
        }
        try {
            logger.warn("No SSL certificate configured — using self-signed ECDSA (14-day) for dev.");
            var now = new Date();
            var expiry = new Date(now.getTime() + 14L * 24 * 60 * 60 * 1000);
            var selfSigned = new SelfSignedCertificate("localhost", now, expiry, "EC", 256);
            certificateHashBase64 = computeCertHash(selfSigned.cert());
            logger.info("WebTransport self-signed certificate SHA-256: {}", certificateHashBase64);
            return QuicSslContextBuilder.forServer(selfSigned.key(), null, selfSigned.cert())
                    .applicationProtocols(Http3.supportedApplicationProtocols())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate self-signed certificate", e);
        }
    }

    private static String computeCertHash(X509Certificate cert) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(cert.getEncoded());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute certificate hash", e);
        }
    }

    // ── HTTP/3 request handler ───────────────────────────────────────────

    /**
     * Netty HTTP/3 request stream handler. Receives raw HTTP/3 frames and
     * dispatches them:
     * <ul>
     *   <li>CONNECT + :protocol=webtransport → WebTransport session</li>
     *   <li>Other methods → bridge to AtmosphereFramework.doCometSupport()</li>
     * </ul>
     */
    /**
     * WebSocket adapter that writes to a QUIC bidirectional stream via HTTP/3 DATA frames.
     * The write target starts as the CONNECT stream (stream 0) but can be redirected
     * to a data stream (stream 4+) when the client creates one.
     */
    private static class QuicWebSocket extends org.atmosphere.websocket.WebSocket {

        private volatile ChannelHandlerContext dataCtx;
        private volatile boolean ready;

        QuicWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        void setDataChannel(ChannelHandlerContext ctx) {
            this.dataCtx = ctx;
        }

        /** Mark the WebSocket as ready to send — suppresses writes during handshake. */
        void markReady() {
            this.ready = true;
        }

        @Override
        public boolean isOpen() {
            return dataCtx != null && dataCtx.channel().isActive();
        }

        @Override
        public org.atmosphere.websocket.WebSocket write(String s) {
            if (!ready) return this;
            var ctx = dataCtx;
            if (ctx != null && ctx.channel().isActive()) {
                // Write raw bytes — the bidi data stream has no HTTP/3 codec
                ctx.writeAndFlush(Unpooled.copiedBuffer(s, StandardCharsets.UTF_8));
            }
            return this;
        }

        @Override
        public org.atmosphere.websocket.WebSocket write(byte[] b, int offset, int length) {
            if (!ready) return this;
            var ctx = dataCtx;
            if (ctx != null && ctx.channel().isActive()) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(b, offset, length));
            }
            return this;
        }

        @Override
        public void close() {
            var ctx = dataCtx;
            if (ctx != null) ctx.close();
        }
    }

    /**
     * Intercepts raw bytes on bidirectional streams to handle WebTransport
     * frame type 0x41 (WT_BIDI_STREAM). The Netty HTTP/3 codec doesn't
     * recognize this frame type, so we intercept before it reaches the codec,
     * strip the WT header, and pass data directly to the Atmosphere bridge.
     */
    private static class WebTransportBidiStreamHandler extends io.netty.channel.ChannelInboundHandlerAdapter {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean isWtStream;
        private boolean headerParsed;
        private WebTransportConnectionState state;

        WebTransportBidiStreamHandler(AtmosphereFramework framework,
                                       java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions) {
            this.framework = framework;
            this.sessions = sessions;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("WT bidi handler channelActive: {}", ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof io.netty.buffer.ByteBuf buf) {
                logger.info("WT bidi handler data: {} bytes, hex={}", buf.readableBytes(),
                        io.netty.buffer.ByteBufUtil.hexDump(buf, buf.readerIndex(),
                                Math.min(buf.readableBytes(), 32)));
                if (!headerParsed) {
                    headerParsed = true;
                    // WebTransport bidi stream starts with: type_varint(0x41) + session_id_varint
                    // Just skip the entire header — it's always the first read on this stream
                    isWtStream = true;
                    // Skip all varint bytes: high bit set = more bytes follow
                    // First varint: stream type (0x41, encoded as 0x40 0x41 in 2-byte varint)
                    skipVarint(buf);
                    // Second varint: session ID
                    skipVarint(buf);
                    if (true) {

                        // Set up Atmosphere bridge
                        var parentChannel = ctx.channel().parent();
                        state = parentChannel != null ? sessions.get(parentChannel) : null;
                        if (state != null && state.webSocket != null) {
                            state.webSocket.setDataChannel(ctx);
                            if (!state.opened) {
                                state.opened = true;
                                var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                                        .getWebSocketProcessor(framework);
                                // Build path + query from the CONNECT path
                                var connectPath = state.path;
                                var qIdx = connectPath.indexOf('?');
                                var pInfo = qIdx >= 0 ? connectPath.substring(0, qIdx) : connectPath;
                                var qs = qIdx >= 0 ? connectPath.substring(qIdx + 1) : "";
                                // Pass query string from CONNECT :path (includes auth tokens, tracking IDs)
                                var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                        .header("Connection", "Upgrade")
                                        .header("Upgrade", "websocket")
                                        .pathInfo(pInfo);
                                if (!qs.isEmpty()) {
                                    request.queryString(qs);
                                }
                                var response = org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(
                                        framework.getAtmosphereConfig(), request, state.webSocket);
                                response.delegateToNativeResponse(false);
                                processor.open(state.webSocket, request, response);
                                state.webSocket.markReady();
                                logger.info("WebTransport bidi stream bridged for {}", state.path);
                            }
                        }

                        // Process remaining data in this buffer
                        if (buf.readableBytes() > 0) {
                            processData(buf);
                        }
                        return; // Don't pass to HTTP/3 codec
                    }
                }
                if (isWtStream) {
                    processData(buf);
                    return; // Don't pass to HTTP/3 codec
                }
            }
            // Not a WT stream — pass to HTTP/3 codec
            super.channelRead(ctx, msg);
        }

        /** Skip a QUIC varint (1, 2, 4, or 8 bytes based on the 2 MSBs). */
        private static void skipVarint(io.netty.buffer.ByteBuf buf) {
            if (buf.readableBytes() == 0) return;
            int first = buf.readByte() & 0xFF;
            int len = 1 << (first >> 6); // 00→1, 01→2, 10→4, 11→8
            buf.skipBytes(len - 1);
        }

        private void processData(io.netty.buffer.ByteBuf buf) {
            if (state != null && state.webSocket != null) {
                var message = buf.toString(StandardCharsets.UTF_8);
                logger.info("WebTransport bidi data: {}", message);
                var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                        .getWebSocketProcessor(framework);
                processor.invokeWebSocketProtocol(state.webSocket, message);
            }
            buf.release();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (isWtStream && state != null && state.webSocket != null) {
                logger.info("WebTransport bidi stream closed");
                var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                        .getWebSocketProcessor(framework);
                processor.close(state.webSocket, 1000);
            }
            super.channelInactive(ctx);
        }
    }

    /**
     * Shared state per QUIC connection: the WebSocket bridge and processor,
     * set by the CONNECT handler and used by data stream handlers.
     */
    private static class WebTransportConnectionState {
        volatile QuicWebSocket webSocket;
        volatile boolean opened;
        String path;
    }

    private static class AtmosphereHttp3Handler extends Http3RequestStreamInboundHandler {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean isDataStream;
        private WebTransportConnectionState state;

        AtmosphereHttp3Handler(AtmosphereFramework framework,
                               java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions) {
            this.framework = framework;
            this.sessions = sessions;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
            var headers = headersFrame.headers();
            var method = headers.method();
            var protocol = headers.protocol();

            if ("CONNECT".contentEquals(method) && protocol != null
                    && "webtransport".contentEquals(protocol)) {
                var path = headers.path() != null ? headers.path().toString() : "/";
                logger.info("WebTransport CONNECT on stream {}: {}", ctx.channel(), path);

                // Accept the WebTransport session (draft-02 negotiation)
                var responseHeaders = new DefaultHttp3HeadersFrame();
                responseHeaders.headers().status("200");
                responseHeaders.headers().add("sec-webtransport-http3-draft", "draft02");
                ctx.write(responseHeaders);
                ctx.flush();

                // Prepare state — defer processor.open() until first DATA frame
                // to avoid writing handshake bytes on the CONNECT stream
                state = new WebTransportConnectionState();
                state.path = path;
                state.webSocket = new QuicWebSocket(framework);
                state.webSocket.setDataChannel(ctx);
                isDataStream = true;

                var parentChannel = ctx.channel().parent();
                if (parentChannel != null) {
                    sessions.put(parentChannel, state);
                }
            } else {
                // This is a data stream — find the connection's WebTransport state
                var parentChannel = ctx.channel().parent();
                state = parentChannel != null ? sessions.get(parentChannel) : null;
                if (state != null && state.webSocket != null) {
                    isDataStream = true;
                    state.webSocket.setDataChannel(ctx);
                    logger.info("WebTransport data stream opened: {}", ctx.channel());

                    // Now that we have a data channel, open the WebSocket bridge
                    if (!state.opened) {
                        state.opened = true;
                        try {
                            var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                                    .getWebSocketProcessor(framework);
                            var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                    .header("Connection", "Upgrade")
                                    .header("Upgrade", "websocket")
                                    .pathInfo(state.path);
                            var response = org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(
                                    framework.getAtmosphereConfig(), request, state.webSocket);
                            processor.open(state.webSocket, request, response);
                            logger.info("WebTransport bridged via WebSocketProcessor for {}", state.path);
                        } catch (Exception e) {
                            logger.error("Failed to bridge WebTransport to Atmosphere", e);
                        }
                    }
                } else {
                    logger.trace("HTTP/3 request: {} {}", method, headers.path());
                }
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            var rawBytes = new byte[dataFrame.content().readableBytes()];
            dataFrame.content().getBytes(dataFrame.content().readerIndex(), rawBytes);
            logger.info("HTTP/3 DATA frame on {}: {} bytes, hex={}, isDataStream={}",
                    ctx.channel(), rawBytes.length,
                    io.netty.buffer.ByteBufUtil.hexDump(rawBytes), isDataStream);
            // If we haven't set up as data stream yet, try to find state from parent
            if (!isDataStream && state == null) {
                var parentChannel = ctx.channel().parent();
                state = parentChannel != null ? sessions.get(parentChannel) : null;
                if (state != null && state.webSocket != null) {
                    isDataStream = true;
                    state.webSocket.setDataChannel(ctx);
                    if (!state.opened) {
                        state.opened = true;
                        try {
                            var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                                    .getWebSocketProcessor(framework);
                            var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                    .header("Connection", "Upgrade")
                                    .header("Upgrade", "websocket")
                                    .pathInfo(state.path);
                            var response = org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(
                                    framework.getAtmosphereConfig(), request, state.webSocket);
                            processor.open(state.webSocket, request, response);
                            logger.info("WebTransport bridged via WebSocketProcessor (from DATA)");
                        } catch (Exception e) {
                            logger.error("Failed to bridge WebTransport", e);
                        }
                    }
                }
            }
            if (isDataStream && state != null && state.webSocket != null) {
                state.webSocket.markReady();
                var data = dataFrame.content();
                var message = data.toString(StandardCharsets.UTF_8);
                logger.info("WebTransport message received: {}", message);

                var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                        .getWebSocketProcessor(framework);
                processor.invokeWebSocketProtocol(state.webSocket, message);
            }
            dataFrame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            if (isDataStream && state != null && state.webSocket != null) {
                logger.info("WebTransport data stream closed");
                var processor = org.atmosphere.cpr.WebSocketProcessorFactory.getDefault()
                        .getWebSocketProcessor(framework);
                processor.close(state.webSocket, 1000);
            }
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("HTTP/3 stream error", cause);
            ctx.close();
        }
    }
}
