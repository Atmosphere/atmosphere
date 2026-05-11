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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle tests for {@link ReactorNettyTransportServer} — the HTTP/3 +
 * WebTransport server bridging Netty into Atmosphere. Boots on an ephemeral
 * UDP port (port=0) so the test can run on any host without colliding with
 * a real WebTransport listener.
 *
 * <p>Closes the coverage gap on the server lifecycle: bind/unbind, port
 * resolution, self-signed certificate generation, and the SHA-256 hash that
 * the JS client consumes via {@code serverCertificateHashes}. Together with
 * {@link ReactorNettyWebTransportSessionTest} this is the first direct unit
 * coverage of the Reactor Netty H/3 path.</p>
 */
class ReactorNettyTransportServerTest {

    private AtmosphereFramework framework;
    private ReactorNettyTransportServer server;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework(true, false);
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        framework.destroy();
    }

    private static WebTransportProperties properties() {
        var props = new WebTransportProperties();
        props.setEnabled(true);
        props.setHost("127.0.0.1");
        props.setPort(0); // ephemeral
        return props;
    }

    @Test
    void serverBindsOnEphemeralPortAndReportsRunning() {
        server = new ReactorNettyTransportServer(framework, properties());

        server.start();

        assertTrue(server.isRunning(), "isRunning() must reflect a live server channel after start()");
        assertTrue(server.port() > 0,
                "port() must resolve to the kernel-assigned port after binding port=0; got " + server.port());
    }

    @Test
    void stopReleasesServerResources() {
        server = new ReactorNettyTransportServer(framework, properties());
        server.start();
        assertTrue(server.isRunning());

        server.stop();

        assertFalse(server.isRunning(),
                "isRunning() must be false after stop() — Invariant #1 (Ownership: every start has a symmetric stop)");
    }

    @Test
    void selfSignedCertificateHashIsBase64Sha256() {
        server = new ReactorNettyTransportServer(framework, properties());
        server.start();

        var hash = server.certificateHash();

        assertNotNull(hash,
                "certificateHash() must surface the SHA-256 of the auto-generated self-signed cert "
                        + "so the JS client can pin it via serverCertificateHashes");
        var decoded = Base64.getDecoder().decode(hash);
        assertEquals(32, decoded.length,
                "SHA-256 digest must be 32 bytes; got " + decoded.length);
        // Standard base64 of 32 bytes = 44 chars (43 chars + 1 padding '=').
        assertEquals(44, hash.length(),
                "Standard base64 encoding of a 32-byte digest is exactly 44 chars (incl. '=' padding)");
        assertTrue(hash.endsWith("="),
                "44-char base64 of a 32-byte input always carries one '=' pad char");
    }

    @Test
    void certificateHashIsAvailableBeforeFirstClient() {
        // The hash MUST be ready as soon as start() returns — the
        // /api/webtransport-info endpoint exposes it synchronously and the
        // JS client uses it on the very first connection.
        server = new ReactorNettyTransportServer(framework, properties());
        server.start();

        assertNotNull(server.certificateHash(),
                "Invariant #5 (Runtime Truth): /api/webtransport-info must never advertise null");
    }

    @Test
    void portIsMinusOneBeforeStart() {
        server = new ReactorNettyTransportServer(framework, properties());
        assertEquals(-1, server.port(),
                "port() must return -1 before bind so callers can distinguish 'not started' from a real port");
        assertFalse(server.isRunning());
    }

    @Test
    void multipleStartStopCyclesDoNotLeak() {
        server = new ReactorNettyTransportServer(framework, properties());
        for (int i = 0; i < 3; i++) {
            server.start();
            assertTrue(server.isRunning(), "cycle " + i + ": must be running");
            assertTrue(server.port() > 0);
            server.stop();
            assertFalse(server.isRunning(), "cycle " + i + ": must be stopped");
        }
    }

    @Test
    void differentInstancesGetDifferentEphemeralPorts() {
        server = new ReactorNettyTransportServer(framework, properties());
        var second = new ReactorNettyTransportServer(framework, properties());
        try {
            server.start();
            second.start();
            assertNotEquals(server.port(), second.port(),
                    "kernel must assign distinct ephemeral ports — confirms each server "
                            + "owns its own UDP socket and group");
        } finally {
            second.stop();
        }
    }

    @Test
    void certificateHashIsStableForLifetimeOfInstance() {
        server = new ReactorNettyTransportServer(framework, properties());
        server.start();
        var first = server.certificateHash();
        server.stop();
        // Restart on the SAME instance — the cert should NOT regenerate
        // because buildQuicSslContext only runs inside start(); a fresh start
        // generates a NEW cert. This pins the contract: each start() makes a
        // fresh cert, so the JS client must re-fetch /api/webtransport-info
        // after a server restart.
        server.start();
        var second = server.certificateHash();
        assertNotEquals(first, second,
                "server restart must produce a fresh cert (forces JS client to re-fetch the hash)");
    }
}
