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
import io.netty.channel.socket.ChannelInputShutdownEvent;
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
import io.netty.handler.codec.quic.QuicStreamResetException;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
            // WebTransport draft version — configurable for interop with evolving specs.
            // Long.decode handles both "0x..." prefixed and plain-hex values, so
            // the default literal "0x2b603742" parses correctly regardless of
            // whether the override property carries the prefix. The previous
            // Long.parseLong(..., 16) call rejected the "0x" prefix and made
            // server.start() fail every time the default was used — surfaced
            // by ReactorNettyTransportServerTest in a clean lifecycle bind.
            var draftId = Long.decode(System.getProperty(
                    "atmosphere.http3.webtransport.draft-setting", "0x2b603742"));
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
                                    // A session no stream ended (idle timeout, lost
                                    // peer) was dropped, not closed by the client.
                                    removed.closeSession(framework, ABNORMAL_CLOSURE);
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
                                            // Report the peer's FIN as an input shutdown instead of
                                            // closing the stream, so it reads as the client leaving.
                                            streamCh.config().setAllowHalfClosure(true);
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
    static class WebTransportBidiStreamHandler extends ChannelInboundHandlerAdapter {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean headerParsed;
        private WebTransportConnectionState state;
        /** Accumulates header bytes across reads until both varints are complete. */
        private CompositeByteBuf headerBuf;
        /** Max frame size to prevent unbounded buffer growth (DoS protection). */
        private static final int MAX_FRAME_BYTES = 1_048_576; // 1 MB
        /** The peer sent FIN on this stream. */
        private boolean peerFinished;
        /** The peer reset this stream because its session ended. */
        private boolean sessionGone;

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

        // Byte-level accumulator to avoid corrupting multi-byte UTF-8 split across frames
        private final io.netty.buffer.CompositeByteBuf byteAccumulator =
                io.netty.buffer.Unpooled.compositeBuffer();

        /**
         * Process incoming data using the bidi stream framing: a
         * {@code 0x00} marker opens a length-prefixed binary frame (marker +
         * 4-byte big-endian length + payload, delivered to the byte-message
         * protocol path untouched); anything else is a newline-delimited
         * UTF-8 text message. Binary payloads must never pass through the
         * text decode/split path — splitting on a payload's {@code 0x0A}
         * bytes and UTF-8 decoding silently corrupted them (Invariant #4).
         */
        private void processData(ByteBuf buf) {
            if (state == null || state.session == null) {
                buf.release();
                return;
            }

            // Accumulate raw bytes first, then scan for complete frames
            byteAccumulator.addComponent(true, buf.retain());
            buf.release();

            // DoS protection: reject oversized frames (+5 headroom for a
            // maximal binary frame's marker and length prefix)
            if (byteAccumulator.readableBytes() > MAX_FRAME_BYTES + 5) {
                logger.warn("WebTransport frame exceeds {} bytes, dropping", MAX_FRAME_BYTES);
                dropAccumulator();
                return;
            }

            var drained = BidiFraming.drain(byteAccumulator, MAX_FRAME_BYTES);
            if (drained.corrupt()) {
                logger.warn("WebTransport binary frame length out of bounds, "
                        + "dropping stream buffer");
                dropAccumulator();
            }
            for (var frame : drained.frames()) {
                var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                        .getWebTransportProcessor(framework);
                switch (frame) {
                    case BidiFraming.Frame.Text text -> {
                        logger.trace("WebTransport bidi message: {}", text.message());
                        processor.invokeWebTransportProtocol(state.session, text.message());
                    }
                    case BidiFraming.Frame.Binary binary -> {
                        logger.trace("WebTransport bidi binary message: {} bytes",
                                binary.payload().length);
                        processor.invokeWebTransportProtocol(state.session,
                                binary.payload(), 0, binary.payload().length);
                    }
                }
            }
        }

        /** Release every accumulated component and reset the read indices. */
        private void dropAccumulator() {
            while (byteAccumulator.numComponents() > 0) {
                byteAccumulator.removeComponent(0);
            }
            byteAccumulator.discardReadBytes();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ChannelInputShutdownEvent) {
                // The peer finished its stream (atmosphere.js disconnect() sends
                // this FIN before its CLOSE capsule): it ended the session.
                peerFinished = true;
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // Flush any remaining buffered TEXT as a final message. A partial
            // binary frame at close is incomplete by definition — decode only
            // complete framed messages (Invariant #4), never text-decode
            // binary remainder.
            if (state != null && state.session != null && byteAccumulator.isReadable()) {
                if (byteAccumulator.getByte(byteAccumulator.readerIndex())
                        == BidiFraming.BINARY_FRAME_MARKER) {
                    logger.warn("WebTransport bidi stream closed mid binary frame — {} "
                            + "buffered bytes dropped", byteAccumulator.readableBytes());
                } else {
                    var remaining = new byte[byteAccumulator.readableBytes()];
                    byteAccumulator.readBytes(remaining);
                    var message = new String(remaining, StandardCharsets.UTF_8);
                    logger.trace("WebTransport bidi final message: {}", message);
                    var processor = org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                            .getWebTransportProcessor(framework);
                    processor.invokeWebTransportProtocol(state.session, message);
                }
            }
            if (state != null && state.session != null && !sessionGone) {
                // A stream the peer did not finish was dropped or reset. A
                // session-gone reset leaves it to the CONNECT stream, which
                // carries how the session ended.
                logger.debug("WebTransport bidi stream closed (peer finished: {})", peerFinished);
                state.closeSession(framework, peerFinished ? NORMAL_CLOSURE : ABNORMAL_CLOSURE);
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
            if (cause instanceof QuicStreamResetException reset
                    && reset.applicationProtocolCode() == WEBTRANSPORT_SESSION_GONE) {
                sessionGone = true;
                logger.debug("WebTransport bidi stream reset: session gone");
            } else {
                logger.error("WebTransport bidi stream error", cause);
            }
            if (byteAccumulator.refCnt() > 0) {
                byteAccumulator.release();
            }
            ctx.close();
        }
    }

    /** A close the peer made on purpose: the client left (WebSocket 1000). */
    static final int NORMAL_CLOSURE = 1000;
    /**
     * The page that held the session went away: a reload, a navigation or a
     * closed tab (WebSocket 1001). The client may come back for what it left.
     */
    static final int GOING_AWAY = 1001;
    /** The peer violated the capsule protocol (WebSocket 1002). */
    static final int PROTOCOL_ERROR = 1002;
    /** The session went away without a close (WebSocket 1006): a dropped client. */
    static final int ABNORMAL_CLOSURE = 1006;
    /** WEBTRANSPORT_SESSION_GONE: the reset code of a stream whose session ended. */
    static final long WEBTRANSPORT_SESSION_GONE = 0x170d7b68L;

    /**
     * The close code of a CLOSE_WEBTRANSPORT_SESSION capsule as the processor
     * reads it: the application error code 0 ("no error") is a page going
     * away, any other code is passed through (1000 is a leave).
     *
     * <p>Code 0 is what Chrome sends when the page that holds the session
     * reloads, navigates away or closes, with the session's bidi stream still
     * open. atmosphere.js {@code disconnect()} sends it too, but only after it
     * finished its bidi stream, which already closed the session as a leave
     * (1000; the session closes once). So a code 0 that finds the session still
     * open reads as the WebSocket 1001 a browser sends from a page going away,
     * which the AI reattach grace treats as a lost transport. Like the HTTP
     * transports' going-away close message, it is the client's word: a client
     * that leaves on purpose without finishing its stream — or whose FIN
     * arrives after its capsule — only has its run cancelled after the bounded
     * grace rather than at once; a client sends 1000 to leave without its FIN.</p>
     */
    static int closeCode(long webTransportErrorCode) {
        if (webTransportErrorCode == 0) {
            return GOING_AWAY;
        }
        return (int) Math.min(webTransportErrorCode, Integer.MAX_VALUE);
    }

    /**
     * Shared state per QUIC connection: the session and path,
     * set by the CONNECT handler and used by data stream handlers.
     *
     * <p>All fields are accessed exclusively on the QUIC connection's
     * event loop thread (Netty I/O thread), so no additional
     * synchronization is needed beyond {@code volatile}.</p>
     */
    static class WebTransportConnectionState {
        volatile ReactorNettyWebTransportSession session;
        volatile boolean opened;
        /** Enforces single bidi stream per WT session (v1). */
        volatile boolean bidiStreamActive;
        String path;
        /** Close-once guard: the CONNECT stream and the bidi stream both end the session. */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * Close the bridged Atmosphere session exactly once. A client close
         * ends the session on several paths (CLOSE capsule, CONNECT stream
         * FIN, bidi stream teardown); only the first reaches the processor,
         * so the application sees one disconnect per session (Invariant #2).
         */
        void closeSession(AtmosphereFramework framework, int closeCode) {
            var current = session;
            if (current == null || !closed.compareAndSet(false, true)) {
                return;
            }
            org.atmosphere.cpr.WebTransportProcessorFactory.getDefault()
                    .getWebTransportProcessor(framework)
                    .close(current, closeCode);
        }
    }

    /**
     * HTTP/3 request stream handler. Dispatches CONNECT + :protocol=webtransport
     * to the WebTransport SPI; returns 501 for all other HTTP/3 methods (v1).
     *
     * <p>Like {@link WebTransportBidiStreamHandler}, data frames use
     * newline-delimited framing to preserve message boundaries. The
     * extended-CONNECT stream itself is the exception: its DATA frames carry
     * HTTP capsules ({@link WebTransportCapsules}), which are decoded as such
     * and never delivered as messages.</p>
     */
    static class AtmosphereHttp3Handler extends Http3RequestStreamInboundHandler {

        private final AtmosphereFramework framework;
        private final java.util.concurrent.ConcurrentMap<Channel, WebTransportConnectionState> sessions;
        private boolean isDataStream;
        /**
         * True on the extended-CONNECT stream itself. Its DATA frames carry
         * HTTP capsules (RFC 9297), never application messages.
         */
        private boolean isSessionStream;
        private WebTransportConnectionState state;
        private final StringBuilder lineBuffer = new StringBuilder();
        /** Accumulates capsule bytes across DATA frames on the CONNECT stream. */
        private CompositeByteBuf capsuleBuf;

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
                isSessionStream = true;

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
            if (isSessionStream) {
                // The CONNECT stream carries capsules, never application
                // messages: decode them as capsules (Invariant #4).
                readCapsules(ctx, dataFrame.content());
                dataFrame.release();
                return;
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

        /**
         * Decode the capsules of the CONNECT stream. A CLOSE capsule ends the
         * session; DRAIN and unknown capsule types (RFC 9297: skip them) carry
         * no application data. Capsule bytes never reach the processor.
         */
        private void readCapsules(ChannelHandlerContext ctx, ByteBuf content) {
            if (capsuleBuf == null) {
                capsuleBuf = ctx.alloc().compositeBuffer();
            }
            capsuleBuf.addComponent(true, content.retain());
            var drained = WebTransportCapsules.drain(capsuleBuf,
                    WebTransportCapsules.MAX_CAPSULE_VALUE_BYTES);
            capsuleBuf.discardReadComponents();
            for (var capsule : drained.capsules()) {
                if (capsule.type() == WebTransportCapsules.CLOSE_WEBTRANSPORT_SESSION) {
                    logger.debug("WebTransport CLOSE_WEBTRANSPORT_SESSION for {} (code {}, reason '{}')",
                            sessionPath(), capsule.closeErrorCode(), capsule.closeReason());
                    endSession(ctx, closeCode(capsule.closeErrorCode()));
                    return;
                } else if (capsule.type() == WebTransportCapsules.DRAIN_WEBTRANSPORT_SESSION) {
                    logger.debug("WebTransport DRAIN_WEBTRANSPORT_SESSION for {}", sessionPath());
                } else {
                    logger.trace("Ignoring WebTransport capsule type 0x{} ({} bytes)",
                            Long.toHexString(capsule.type()), capsule.value().length);
                }
            }
            if (drained.corrupt()) {
                logger.warn("WebTransport capsule larger than {} bytes on {}, closing the session",
                        WebTransportCapsules.MAX_CAPSULE_VALUE_BYTES, sessionPath());
                endSession(ctx, PROTOCOL_ERROR);
            }
        }

        private String sessionPath() {
            return state != null ? state.path : null;
        }

        /**
         * End the WebTransport session: close the Atmosphere side once, then
         * the session's bidi stream and this CONNECT stream.
         */
        private void endSession(ChannelHandlerContext ctx, int closeCode) {
            releaseCapsuleBuf();
            if (state != null) {
                state.closeSession(framework, closeCode);
                if (state.session != null) {
                    state.session.close();
                }
            }
            ctx.close();
        }

        private void releaseCapsuleBuf() {
            if (capsuleBuf != null) {
                capsuleBuf.release();
                capsuleBuf = null;
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            releaseCapsuleBuf();
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
            if (isSessionStream) {
                // FIN on the CONNECT stream terminates the session. Unread
                // bytes are an incomplete capsule, never a message. Without a
                // CLOSE capsule it is a close with code 0 (draft-ietf-webtrans-http3),
                // read as closeCode(0) reads it.
                logger.debug("WebTransport CONNECT stream closed");
                endSession(ctx, closeCode(0));
                return;
            }
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
                state.closeSession(framework, NORMAL_CLOSURE);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // A stream that goes away without a FIN or a CLOSE capsule (reset,
            // lost connection) dropped its session. After a clean end the
            // session is already closed and this is a no-op.
            if (isDataStream && state != null) {
                releaseCapsuleBuf();
                state.closeSession(framework, ABNORMAL_CLOSURE);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("HTTP/3 stream error", cause);
            ctx.close();
        }
    }
}
