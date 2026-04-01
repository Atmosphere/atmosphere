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
 * servlet container. In v1, only WebTransport extended CONNECT requests
 * are supported — regular HTTP/3 requests receive a 501 response.
 * WebTransport sessions create bidirectional QUIC streams bridged into
 * Atmosphere via the {@link org.atmosphere.webtransport.WebTransportProcessor} SPI.</p>
 *
 * <p><strong>Auth note:</strong> Chrome strips query parameters from the
 * WebTransport CONNECT {@code :path}. Auth tokens must use post-connection
 * authentication (e.g., first message), not query parameters.</p>
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
            logger.debug("HTTP/3 SETTINGS frame: {}", settingsFrame);

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
                            logger.debug("QUIC connection established: {}", ch);
                            // Clean up session state when the QUIC connection closes
                            ch.closeFuture().addListener(f -> {
                                var removed = wtSessions.remove(ch);
                                if (removed != null) {
                                    logger.debug("QUIC connection closed, cleaned up WT session: {}", ch);
                                }
                            });
                            try {
                                var connHandler = new Http3ServerConnectionHandler(
                                        new ChannelInitializer<QuicStreamChannel>() {
                                            @Override
                                            protected void initChannel(QuicStreamChannel streamCh) {
                                                logger.debug("HTTP/3 request stream opened: {}", streamCh);
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
                                                    logger.debug("WT bidi stream registered OK, reading: {}", streamCh);
                                                } else {
                                                    logger.error("WT bidi stream register failed", f.cause());
                                                }
                                            });
                                            logger.debug("WT bidi stream intercepted: {}", streamCh);
                                            return; // DON'T pass to Http3ServerConnectionHandler
                                        }
                                        super.channelRead(connCtx, msg);
                                    }
                                });
                                ch.pipeline().addLast(connHandler);
                                logger.debug("Http3ServerConnectionHandler installed, pipeline: {}",
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

    // ── Shared helpers ────────────────────────────────────────────────────

    /**
     * Split a CONNECT {@code :path} into pathInfo and queryString on the
     * request builder. Handles paths with or without query parameters.
     */
    private static void applyPathAndQuery(
            org.atmosphere.cpr.AtmosphereRequest request, String connectPath) {
        int qIdx = connectPath.indexOf('?');
        request.pathInfo(qIdx >= 0 ? connectPath.substring(0, qIdx) : connectPath);
        if (qIdx >= 0) {
            request.queryString(connectPath.substring(qIdx + 1));
        }
    }

    // ── HTTP/3 request handler ───────────────────────────────────────────

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
        private byte[] trailingBytes = new byte[0];

        WebTransportBidiStreamHandler(AtmosphereFramework framework,
                                       java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions) {
            this.framework = framework;
            this.sessions = sessions;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.trace("WT bidi handler channelActive: {}", ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof io.netty.buffer.ByteBuf buf) {
                if (logger.isTraceEnabled()) {
                    logger.trace("WT bidi handler data: {} bytes, hex={}", buf.readableBytes(),
                            io.netty.buffer.ByteBufUtil.hexDump(buf, buf.readerIndex(),
                                    Math.min(buf.readableBytes(), 32)));
                }
                if (!headerParsed) {
                    headerParsed = true;
                    isWtStream = true;
                    // WebTransport bidi stream starts with: type_varint(0x41) + session_id_varint
                    skipVarint(buf);
                    skipVarint(buf);

                    // Set up Atmosphere bridge via WebTransport SPI
                    var parentChannel = ctx.channel().parent();
                    state = parentChannel != null ? sessions.get(parentChannel) : null;
                    if (state != null && !state.opened) {
                        state.opened = true;
                        // Create the session now that we have the data channel
                        state.session = new ReactorNettyWebTransportSession(
                                framework.getAtmosphereConfig(), ctx.channel());
                        var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                                .getWebTransportProcessor(framework);
                        var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                .header("Connection", "Upgrade")
                                .header("Upgrade", "websocket");
                        applyPathAndQuery(request, state.path);
                        processor.open(state.session, request,
                                org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(request));
                        logger.debug("WebTransport bidi stream bridged for {}", state.path);
                    }

                    // Process remaining data in this buffer
                    if (buf.readableBytes() > 0) {
                        processData(buf);
                    } else {
                        buf.release();
                    }
                    return;
                }
                if (isWtStream) {
                    processData(buf);
                    return;
                }
            }
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
            if (state == null || state.session == null) {
                buf.release();
                return;
            }
            // Combine any leftover trailing bytes with new data
            byte[] data;
            int readable = buf.readableBytes();
            if (trailingBytes.length > 0) {
                data = new byte[trailingBytes.length + readable];
                System.arraycopy(trailingBytes, 0, data, 0, trailingBytes.length);
                buf.readBytes(data, trailingBytes.length, readable);
            } else {
                data = new byte[readable];
                buf.readBytes(data);
            }
            buf.release();

            // Find the last complete UTF-8 character boundary
            int boundary = findUtf8Boundary(data);
            if (boundary < data.length) {
                trailingBytes = java.util.Arrays.copyOfRange(data, boundary, data.length);
            } else {
                trailingBytes = new byte[0];
            }

            if (boundary > 0) {
                var message = new String(data, 0, boundary, StandardCharsets.UTF_8);
                logger.trace("WebTransport bidi data: {}", message);
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.invokeWebTransportProtocol(state.session, message);
            }
        }

        /**
         * Find the last complete UTF-8 character boundary in a byte array.
         * Scans backwards from the end to detect incomplete multi-byte sequences.
         *
         * @return index up to which the bytes form complete UTF-8 characters
         */
        static int findUtf8Boundary(byte[] data) {
            int len = data.length;
            if (len == 0) return 0;
            // Check up to 3 bytes from the end for an incomplete sequence
            for (int i = 1; i <= Math.min(3, len); i++) {
                int b = data[len - i] & 0xFF;
                if (b < 0x80) {
                    return len;
                }
                if (b >= 0xC0) {
                    int expected;
                    if (b < 0xE0) expected = 2;
                    else if (b < 0xF0) expected = 3;
                    else expected = 4;
                    return i >= expected ? len : len - i;
                }
            }
            return len;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (isWtStream && state != null && state.session != null) {
                logger.debug("WebTransport bidi stream closed");
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.close(state.session, 1000);
            }
            super.channelInactive(ctx);
        }
    }

    /**
     * Shared state per QUIC connection: the session and path,
     * set by the CONNECT handler and used by data stream handlers.
     */
    private static class WebTransportConnectionState {
        volatile ReactorNettyWebTransportSession session;
        volatile boolean opened;
        String path;
    }

    /**
     * HTTP/3 request stream handler. Dispatches CONNECT + :protocol=webtransport
     * to the WebTransport SPI; returns 501 for all other HTTP/3 methods (v1).
     */
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
                logger.debug("WebTransport CONNECT on stream {}: {}", ctx.channel(), path);

                // Accept the WebTransport session (draft-02 negotiation)
                var responseHeaders = new DefaultHttp3HeadersFrame();
                responseHeaders.headers().status("200");
                responseHeaders.headers().add("sec-webtransport-http3-draft", "draft02");
                ctx.write(responseHeaders);
                ctx.flush();

                // Prepare state — defer session creation until bidi stream arrives
                // (we need the data channel for the session)
                state = new WebTransportConnectionState();
                state.path = path;
                isDataStream = true;

                var parentChannel = ctx.channel().parent();
                if (parentChannel != null) {
                    sessions.put(parentChannel, state);
                }
            } else {
                // Check if this is a data stream for an existing WT connection
                var parentChannel = ctx.channel().parent();
                state = parentChannel != null ? sessions.get(parentChannel) : null;
                if (state != null) {
                    isDataStream = true;
                    logger.debug("WebTransport data stream opened: {}", ctx.channel());

                    if (!state.opened) {
                        state.opened = true;
                        try {
                            state.session = new ReactorNettyWebTransportSession(
                                    framework.getAtmosphereConfig(), ctx.channel());
                            var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                                    .getWebTransportProcessor(framework);
                            var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                    .header("Connection", "Upgrade")
                                    .header("Upgrade", "websocket");
                            applyPathAndQuery(request, state.path);
                            processor.open(state.session, request,
                                    org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(request));
                            logger.debug("WebTransport bridged via processor for {}", state.path);
                        } catch (Exception e) {
                            logger.error("Failed to bridge WebTransport to Atmosphere", e);
                        }
                    }
                } else {
                    // v1: only WebTransport is supported on this sidecar
                    logger.debug("Non-WebTransport HTTP/3 request: {} {} — returning 501",
                            method, headers.path());
                    var reject = new DefaultHttp3HeadersFrame();
                    reject.headers().status("501");
                    ctx.writeAndFlush(reject)
                            .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                }
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            if (logger.isTraceEnabled()) {
                logger.trace("HTTP/3 DATA frame on {}: {} bytes, isDataStream={}",
                        ctx.channel(), dataFrame.content().readableBytes(), isDataStream);
            }
            // Late-arriving data stream — try to find state from parent
            if (!isDataStream && state == null) {
                var parentChannel = ctx.channel().parent();
                state = parentChannel != null ? sessions.get(parentChannel) : null;
                if (state != null) {
                    isDataStream = true;
                    if (!state.opened) {
                        state.opened = true;
                        try {
                            state.session = new ReactorNettyWebTransportSession(
                                    framework.getAtmosphereConfig(), ctx.channel());
                            var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                                    .getWebTransportProcessor(framework);
                            var request = org.atmosphere.cpr.AtmosphereRequestImpl.newInstance()
                                    .header("Connection", "Upgrade")
                                    .header("Upgrade", "websocket");
                            applyPathAndQuery(request, state.path);
                            processor.open(state.session, request,
                                    org.atmosphere.cpr.AtmosphereResponseImpl.newInstance(request));
                            logger.debug("WebTransport bridged via processor (from DATA)");
                        } catch (Exception e) {
                            logger.error("Failed to bridge WebTransport", e);
                        }
                    }
                }
            }
            if (isDataStream && state != null && state.session != null) {
                var data = dataFrame.content();
                var message = data.toString(StandardCharsets.UTF_8);
                logger.trace("WebTransport message received: {}", message);

                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.invokeWebTransportProtocol(state.session, message);
            }
            dataFrame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            if (isDataStream && state != null && state.session != null) {
                logger.debug("WebTransport data stream closed");
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.close(state.session, 1000);
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
