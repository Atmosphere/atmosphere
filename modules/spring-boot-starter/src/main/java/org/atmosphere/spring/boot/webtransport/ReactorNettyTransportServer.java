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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.spring.boot.AtmosphereProperties.WebTransportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.netty.http.Http3SslContextSpec;

/**
 * Spring-managed Reactor Netty HTTP/3 server that bridges HTTP/3 requests
 * and WebTransport sessions into the Atmosphere framework. This is the
 * modern equivalent of the old Nettosphere {@code BridgeRuntime}, using
 * Reactor Netty's production-ready HTTP/3 support.
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
    private DisposableServer server;
    private String certificateHashBase64;

    public ReactorNettyTransportServer(AtmosphereFramework framework,
                                        WebTransportProperties properties) {
        this.framework = framework;
        this.properties = properties;
    }

    /**
     * Start the HTTP/3 server on the configured UDP port.
     */
    public void start() {
        try {
            var httpServer = HttpServer.create()
                    .host(properties.getHost())
                    .port(properties.getPort())
                    .protocol(HttpProtocol.HTTP3)
                    .secure(sslSpec -> sslSpec.sslContext(buildHttp3SslContext()))
                    .handle(this::handleRequest);

            server = httpServer.bindNow();
            logger.info("Atmosphere HTTP/3 + WebTransport server started on {}:{}",
                    properties.getHost(), properties.getPort());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start HTTP/3 server", e);
        }
    }

    /**
     * Stop the server and release resources.
     */
    public void stop() {
        if (server != null) {
            server.disposeNow();
            logger.info("Atmosphere HTTP/3 + WebTransport server stopped");
        }
        org.atmosphere.cpr.WebTransportProcessorFactory.getDefault().destroy();
    }

    public boolean isRunning() {
        return server != null && !server.isDisposed();
    }

    public int port() {
        return server != null ? server.port() : -1;
    }

    /**
     * Handle an incoming HTTP/3 request by bridging it into the Atmosphere
     * framework. This is the Nettosphere pattern: create fake
     * {@link org.atmosphere.cpr.AtmosphereRequest} / {@link AtmosphereResponse}
     * and dispatch through {@code doCometSupport()}.
     */
    private Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        logger.trace("HTTP/3 request: {} {}", request.method(), request.uri());

        return request.receive().aggregate().asString()
                .defaultIfEmpty("")
                .flatMap(body -> {
                    try {
                        var atmosRequest = AtmosphereRequestBridge.wrap(request, "");
                        if (!body.isEmpty()) {
                            atmosRequest.body(body);
                        }

                        var atmosResponse = AtmosphereResponseBridge.wrap(atmosRequest);
                        framework.doCometSupport(atmosRequest, atmosResponse);

                        // Write the response back to the Reactor Netty channel
                        int status = atmosResponse.getStatus();
                        response.status(status);

                        // Copy response headers
                        for (var header : atmosResponse.headers().entrySet()) {
                            response.addHeader(header.getKey(), header.getValue());
                        }

                        // Get the response body from the AsyncIOWriter
                        String responseBody = atmosResponse.toString();
                        if (responseBody != null && !responseBody.isEmpty()) {
                            return response.sendString(Mono.just(responseBody)).then();
                        }

                        return response.send();
                    } catch (Exception e) {
                        logger.error("Error bridging HTTP/3 request to Atmosphere", e);
                        response.status(500);
                        return response.sendString(Mono.just("Internal Server Error")).then();
                    }
                });
    }

    // SelfSignedCertificate is deprecated in Netty 4.2 but the replacement
    // (Bouncy Castle KeyPairGenerator) is a heavier dependency for dev-only use.
    @SuppressWarnings("deprecation")
    private Http3SslContextSpec buildHttp3SslContext() {
        var sslProps = properties.getSsl();
        if (sslProps.getCertificate() != null && sslProps.getPrivateKey() != null) {
            var certFile = Path.of(sslProps.getCertificate()).toFile();
            var keyFile = Path.of(sslProps.getPrivateKey()).toFile();
            return Http3SslContextSpec.forServer(keyFile, sslProps.getPrivateKeyPassword(), certFile);
        }
        // Fall back to ECDSA self-signed cert for development.
        // Chrome's serverCertificateHashes requires ECDSA + max 14-day validity.
        try {
            logger.warn("No SSL certificate configured for WebTransport — using self-signed ECDSA certificate (14-day). "
                    + "Configure atmosphere.web-transport.ssl.certificate and "
                    + "atmosphere.web-transport.ssl.private-key for production.");
            var now = new Date();
            var expiry = new Date(now.getTime() + 14L * 24 * 60 * 60 * 1000);
            var selfSigned = new SelfSignedCertificate("localhost", now, expiry, "EC", 256);
            certificateHashBase64 = computeCertHash(selfSigned.cert());
            logger.info("WebTransport self-signed certificate SHA-256: {}", certificateHashBase64);
            return Http3SslContextSpec.forServer(
                    selfSigned.key(), null, selfSigned.cert());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate self-signed certificate", e);
        }
    }

    /**
     * Return the base64-encoded SHA-256 hash of the server certificate,
     * for use with the browser's {@code serverCertificateHashes} option.
     * Only available after {@link #start()} and only for self-signed certs.
     */
    public String certificateHash() {
        return certificateHashBase64;
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
}
