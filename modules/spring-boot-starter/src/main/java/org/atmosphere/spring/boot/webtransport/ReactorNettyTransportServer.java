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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import io.netty.handler.codec.quic.QuicStreamType;
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
    private volatile Channel serverChannel;
    private volatile IoEventLoopGroup group;
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
            // WebTransport draft version — configurable for interop with evolving specs
            var draftId = Long.parseLong(System.getProperty(
                    "atmosphere.http3.webtransport.draft-setting", "0x2b603742"), 16);
            settings.put(draftId, 1L);       // SETTINGS_WEBTRANS_DRAFT (default: draft-02)
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
                    .tokenHandler(resolveQuicTokenHandler())
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
                                        new ChannelInboundHandlerAdapter(),
                                        null, settingsFrame, true,
                                        (id, value) -> true);
                                // Intercept child streams BEFORE Http3ServerConnectionHandler
                                // to catch WebTransport bidi streams (frame type 0x41)
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext connCtx, Object msg) throws Exception {
                                        if (msg instanceof QuicStreamChannel streamCh
                                                && streamCh.type() == QuicStreamType.BIDIRECTIONAL) {
                                            var connState = wtSessions.get(ch);
                                            if (connState == null) {
                                                super.channelRead(connCtx, msg);
                                                return;
                                            }
                                            // Enforce single bidi stream per WT session (v1)
                                            if (connState.bidiStreamActive) {
                                                logger.warn("Rejecting additional bidi stream on WT session {}",
                                                        connState.path);
                                                ch.eventLoop().register(streamCh).addListener(f -> {
                                                    if (f.isSuccess()) {
                                                        streamCh.close();
                                                    }
                                                });
                                                return;
                                            }
                                            connState.bidiStreamActive = true;
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

    private io.netty.handler.codec.quic.QuicTokenHandler resolveQuicTokenHandler() {
        // InsecureQuicTokenHandler skips source-address validation.
        // Netty does not yet provide a SecureQuicTokenHandler, so we always use
        // insecure for now but log a warning for production awareness.
        if (!"true".equalsIgnoreCase(
                System.getProperty("atmosphere.http3.insecure-token-acknowledged"))) {
            logger.info("Using InsecureQuicTokenHandler (Netty default). "
                    + "Set -Datmosphere.http3.insecure-token-acknowledged=true to suppress.");
        }
        return io.netty.handler.codec.quic.InsecureQuicTokenHandler.INSTANCE;
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
     *
     * <h3>Framing</h3>
     * <p>QUIC streams are byte streams with no inherent message boundaries.
     * This handler uses newline ({@code \n}) as a message delimiter: the
     * client appends {@code \n} after each logical message, and this handler
     * buffers incoming data, splits on {@code \n}, and delivers each
     * complete line as a separate message to the Atmosphere protocol handler.
     * Incomplete trailing data is buffered until the next read.</p>
     *
     * <h3>Varint header parsing</h3>
     * <p>The first bytes on a WT bidi stream are two QUIC varints
     * (frame type 0x41 + session ID). This handler accumulates bytes
     * across reads until both varints are fully available, making the
     * parsing safe against QUIC packet fragmentation.</p>
     *
     * <p><strong>v1 limitation:</strong> only text (UTF-8) messages are
     * supported. Binary payloads are not handled; a future version may
     * add a content-type framing byte to distinguish text from binary.</p>
     */
    private static class WebTransportBidiStreamHandler extends ChannelInboundHandlerAdapter {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean headerParsed;
        private WebTransportConnectionState state;
        /** Accumulates header bytes across reads until both varints are complete. */
        private CompositeByteBuf headerBuf;
        /** Max frame size to prevent unbounded buffer growth (DoS protection). */
        private static final int MAX_FRAME_BYTES = 1_048_576; // 1 MB

        WebTransportBidiStreamHandler(AtmosphereFramework framework,
                                       java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions) {
            this.framework = framework;
            this.sessions = sessions;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                super.channelRead(ctx, msg);
                return;
            }
            if (logger.isTraceEnabled()) {
                logger.trace("WT bidi handler data: {} bytes, hex={}", buf.readableBytes(),
                        ByteBufUtil.hexDump(buf, buf.readerIndex(),
                                Math.min(buf.readableBytes(), 32)));
            }
            if (!headerParsed) {
                if (!parseHeader(ctx, buf)) {
                    // Need more bytes for the header; buf ownership transferred to headerBuf
                    return;
                }
                // Header parsed — set up Atmosphere bridge
                var parentChannel = ctx.channel().parent();
                state = parentChannel != null ? sessions.get(parentChannel) : null;
                if (state != null && !state.opened) {
                    state.opened = true;
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
                // Process remaining data after the header (from headerBuf, not buf —
                // buf's ownership was transferred to headerBuf in parseHeader)
                if (headerBuf.readableBytes() > 0) {
                    // Copy remaining bytes to a standalone buffer before releasing headerBuf
                    var remaining = headerBuf.readBytes(headerBuf.readableBytes());
                    headerBuf.release();
                    headerBuf = null;
                    processData(remaining);
                } else {
                    headerBuf.release();
                    headerBuf = null;
                }
                return;
            }
            processData(buf);
        }

        /**
         * Parse the WT bidi stream header (type varint + session_id varint).
         * Accumulates bytes across reads. Returns {@code true} when both varints
         * are fully parsed, leaving {@code buf} positioned after the header.
         * Returns {@code false} if more bytes are needed.
         */
        private boolean parseHeader(ChannelHandlerContext ctx, ByteBuf buf) {
            if (headerBuf == null) {
                headerBuf = ctx.alloc().compositeBuffer();
            }
            // addComponent takes ownership of buf — no retain() needed.
            // The caller must NOT release buf after this point.
            headerBuf.addComponent(true, buf);

            // Try to skip both varints; need at least 2 bytes minimum (1+1)
            if (headerBuf.readableBytes() < 2) {
                return false;
            }
            int saved = headerBuf.readerIndex();
            int needed = varintLength(headerBuf);
            if (headerBuf.readableBytes() < needed - 1) {
                headerBuf.readerIndex(saved);
                return false;
            }
            headerBuf.skipBytes(needed - 1);

            if (headerBuf.readableBytes() == 0) {
                headerBuf.readerIndex(saved);
                return false;
            }
            int needed2 = varintLength(headerBuf);
            if (headerBuf.readableBytes() < needed2 - 1) {
                headerBuf.readerIndex(saved);
                return false;
            }
            headerBuf.skipBytes(needed2 - 1);

            // Header fully parsed. Extract any remaining data after the varints.
            headerParsed = true;
            return true;
        }

        /**
         * Read the first byte of a QUIC varint and return its total length
         * (1, 2, 4, or 8 bytes based on the 2 MSBs). Advances the reader
         * index by 1.
         */
        private static int varintLength(ByteBuf buf) {
            int first = buf.readByte() & 0xFF;
            return 1 << (first >> 6); // 00→1, 01→2, 10→4, 11→8
        }

        /**
         * Process incoming data using newline-delimited framing.
         * Buffers partial lines and delivers each complete {@code \n}-terminated
         * line as a separate message to the Atmosphere protocol handler.
         */
        /** Byte-level accumulator to avoid corrupting multi-byte UTF-8 split across frames. */
        private final io.netty.buffer.CompositeByteBuf byteAccumulator =
                io.netty.buffer.Unpooled.compositeBuffer();

        private void processData(ByteBuf buf) {
            if (state == null || state.session == null) {
                buf.release();
                return;
            }

            // Accumulate raw bytes first, then scan for newline delimiter
            byteAccumulator.addComponent(true, buf.retain());
            buf.release();

            // DoS protection: reject oversized frames
            if (byteAccumulator.readableBytes() > MAX_FRAME_BYTES) {
                logger.warn("WebTransport frame exceeds {} bytes, dropping", MAX_FRAME_BYTES);
                // Release all component buffers, not just reset indices
                while (byteAccumulator.numComponents() > 0) {
                    byteAccumulator.removeComponent(0);
                }
                byteAccumulator.discardReadBytes();
                return;
            }

            // Extract complete newline-delimited frames from the byte accumulator
            int nlIdx;
            while ((nlIdx = findNewline(byteAccumulator)) >= 0) {
                var frameBytes = new byte[nlIdx];
                byteAccumulator.readBytes(frameBytes);
                byteAccumulator.readByte(); // consume the newline
                byteAccumulator.discardReadBytes();

                var message = new String(frameBytes, StandardCharsets.UTF_8);
                if (message.isEmpty()) {
                    continue;
                }
                logger.trace("WebTransport bidi message: {}", message);
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.invokeWebTransportProtocol(state.session, message);
            }
        }

        private static int findNewline(ByteBuf buf) {
            for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
                if (buf.getByte(i) == '\n') {
                    return i - buf.readerIndex();
                }
            }
            return -1;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // Flush any remaining buffered data as a final message
            if (state != null && state.session != null && byteAccumulator.isReadable()) {
                var remaining = new byte[byteAccumulator.readableBytes()];
                byteAccumulator.readBytes(remaining);
                var message = new String(remaining, StandardCharsets.UTF_8);
                logger.trace("WebTransport bidi final message: {}", message);
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.invokeWebTransportProtocol(state.session, message);
            }
            if (state != null && state.session != null) {
                logger.debug("WebTransport bidi stream closed");
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.close(state.session, 1000);
            }
            if (headerBuf != null) {
                headerBuf.release();
                headerBuf = null;
            }
            if (byteAccumulator.refCnt() > 0) {
                byteAccumulator.release();
            }
            // Reset bidi flag so the session can open a new bidi stream
            if (state != null) {
                state.bidiStreamActive = false;
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("WebTransport bidi stream error", cause);
            if (byteAccumulator.refCnt() > 0) {
                byteAccumulator.release();
            }
            ctx.close();
        }
    }

    /**
     * Shared state per QUIC connection: the session and path,
     * set by the CONNECT handler and used by data stream handlers.
     *
     * <p>All fields are accessed exclusively on the QUIC connection's
     * event loop thread (Netty I/O thread), so no additional
     * synchronization is needed beyond {@code volatile}.</p>
     */
    private static class WebTransportConnectionState {
        volatile ReactorNettyWebTransportSession session;
        volatile boolean opened;
        /** Enforces single bidi stream per WT session (v1). */
        volatile boolean bidiStreamActive;
        String path;
    }

    /**
     * HTTP/3 request stream handler. Dispatches CONNECT + :protocol=webtransport
     * to the WebTransport SPI; returns 501 for all other HTTP/3 methods (v1).
     *
     * <p>Like {@link WebTransportBidiStreamHandler}, data frames use
     * newline-delimited framing to preserve message boundaries.</p>
     */
    private static class AtmosphereHttp3Handler extends Http3RequestStreamInboundHandler {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean isDataStream;
        private WebTransportConnectionState state;
        private final StringBuilder lineBuffer = new StringBuilder();

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
                // Mirror the draft version from the client request, or use configured default
                var clientDraft = headers.get("sec-webtransport-http3-draft");
                var draftVersion = clientDraft != null ? clientDraft.toString()
                        : System.getProperty("atmosphere.http3.webtransport.draft-version", "draft02");
                responseHeaders.headers().add("sec-webtransport-http3-draft", draftVersion);
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
                    openSessionIfNeeded(ctx);
                } else {
                    // v1: only WebTransport is supported on this sidecar
                    logger.debug("Non-WebTransport HTTP/3 request: {} {} — returning 501",
                            method, headers.path());
                    var reject = new DefaultHttp3HeadersFrame();
                    reject.headers().status("501");
                    ctx.writeAndFlush(reject)
                            .addListener(ChannelFutureListener.CLOSE);
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
                    openSessionIfNeeded(ctx);
                }
            }
            if (isDataStream && state != null && state.session != null) {
                var data = dataFrame.content();
                var text = data.toString(StandardCharsets.UTF_8);
                lineBuffer.append(text);

                // Deliver each complete newline-delimited message
                int nlIdx;
                while ((nlIdx = lineBuffer.indexOf("\n")) >= 0) {
                    var message = lineBuffer.substring(0, nlIdx);
                    lineBuffer.delete(0, nlIdx + 1);
                    if (message.isEmpty()) {
                        continue;
                    }
                    logger.trace("WebTransport message received: {}", message);
                    var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                            .getWebTransportProcessor(framework);
                    processor.invokeWebTransportProtocol(state.session, message);
                }
            }
            dataFrame.release();
        }

        /** Open the Atmosphere session if this is the first data stream. */
        private void openSessionIfNeeded(ChannelHandlerContext ctx) {
            if (state != null && !state.opened) {
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
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            // Flush any remaining buffered data as a final message
            if (isDataStream && state != null && state.session != null && !lineBuffer.isEmpty()) {
                var message = lineBuffer.toString();
                lineBuffer.setLength(0);
                logger.trace("WebTransport final message: {}", message);
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                processor.invokeWebTransportProtocol(state.session, message);
            }
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
