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
import org.atmosphere.spring.boot.AtmosphereProperties;
import org.atmosphere.spring.boot.AtmosphereProperties.WebTransportProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the TLS certificate handoff path:
 * {@code ReactorNettyTransportServer} → {@code WebTransportInfoController}
 * → JSON the JS client consumes via {@code serverCertificateHashes}.
 *
 * <p>Closes the gap where the cert generation logic was unit-covered
 * (server tests) and the JS consumer was unit-covered
 * ({@code webtransport.test.ts}) but the wire-format contract between them
 * had no integration test. A regression in either side — base64 vs hex
 * encoding, missing port field, accidental cert leak when the sidecar is
 * down — would have shipped silently.</p>
 *
 * <p>Uses {@code AtmosphereProperties} + {@code ReactorNettyTransportServer}
 * directly rather than a {@code @SpringBootTest} so the JVM cost stays low
 * and the failure surface is the controller logic itself, not the entire
 * Boot application context.</p>
 */
class WebTransportInfoControllerTest {

    private AtmosphereFramework framework;
    private ReactorNettyTransportServer server;
    private AtmosphereProperties properties;
    private WebTransportInfoController controller;

    @BeforeEach
    void setUp() {
        framework = new AtmosphereFramework(true, false);
        properties = new AtmosphereProperties();
        var wt = new WebTransportProperties();
        wt.setEnabled(true);
        wt.setHost("127.0.0.1");
        wt.setPort(0); // ephemeral
        properties.setWebTransport(wt);
        server = new ReactorNettyTransportServer(framework, wt);
        controller = new WebTransportInfoController(server, properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        framework.destroy();
    }

    @Test
    void infoReportsActualBoundPortWhenRunning() {
        server.start();
        var info = controller.info();

        assertEquals(Boolean.TRUE, info.get("enabled"),
                "controller MUST advertise enabled=true once the sidecar is bound (Invariant #5: Runtime Truth)");
        var port = (Integer) info.get("port");
        assertNotNull(port);
        assertTrue(port > 0,
                "port must reflect the kernel-assigned ephemeral port, not the configured 0");
        assertEquals(0, info.get("configuredPort"),
                "configuredPort must echo the requested 0 so the client can distinguish the configured vs actual port");
    }

    @Test
    void infoReportsEnabledFalseWhenServerNotRunning() {
        // Server constructed but never start()'d — the controller MUST report
        // enabled=false so the JS client does not attempt a WebTransport
        // connection to a dead sidecar (Invariant #5).
        var info = controller.info();

        assertEquals(Boolean.FALSE, info.get("enabled"),
                "enabled MUST be false when the sidecar isn't bound");
        assertNull(info.get("certificateHash"),
                "certificateHash MUST NOT be exposed when the server isn't running — "
                        + "client would otherwise pin to a hash that never serves traffic");
    }

    @Test
    void certificateHashRoundTripsAsBase64Sha256() {
        server.start();
        var info = controller.info();

        var hash = (String) info.get("certificateHash");
        assertNotNull(hash, "running server must include certificateHash in info response");

        // Same shape pinned in ReactorNettyTransportServerTest, but verifying
        // again from the controller boundary catches a regression where the
        // controller might re-encode (hex / base64url / raw) before exposing.
        assertEquals(44, hash.length(),
                "JS client expects standard base64 SHA-256 (44 chars including '=' pad)");
        var bytes = Base64.getDecoder().decode(hash);
        assertEquals(32, bytes.length, "decoded digest must be exactly 32 bytes");
    }

    @Test
    void hashStaysUnchangedAcrossMultipleInfoCalls() {
        // The controller must NOT regenerate the cert per-call. The JS client
        // calls /api/webtransport-info once on app load and pins the hash;
        // a per-call regeneration would break the pin.
        server.start();
        var first = (String) controller.info().get("certificateHash");
        var second = (String) controller.info().get("certificateHash");
        var third = (String) controller.info().get("certificateHash");

        assertNotNull(first);
        assertEquals(first, second, "info() MUST return a stable hash for the lifetime of a server instance");
        assertEquals(second, third);
    }

    @Test
    void infoSurfacesEnabledFlippingOnRestart() {
        // Lifecycle: not-started → running → stopped. The enabled flag
        // must track the bind state on every transition. Catches a class of
        // bug where the controller caches enabled=true after a stop.
        assertEquals(Boolean.FALSE, controller.info().get("enabled"));
        server.start();
        assertEquals(Boolean.TRUE, controller.info().get("enabled"));
        server.stop();
        assertEquals(Boolean.FALSE, controller.info().get("enabled"),
                "enabled MUST flip back to false after stop() (Invariant #5)");
        assertNull(controller.info().get("certificateHash"),
                "post-stop info() MUST omit the cert hash");
    }

    @Test
    void infoIncludesEveryFieldTheJsClientReadsFrom() {
        server.start();
        var info = controller.info();
        // Pin the wire-format contract — these are the fields the
        // atmosphere.js webtransport transport reads from the response.
        // Adding fields is fine; removing or renaming would break clients.
        assertTrue(info.containsKey("port"), "JS client reads `port`");
        assertTrue(info.containsKey("configuredPort"), "JS client reads `configuredPort`");
        assertTrue(info.containsKey("enabled"), "JS client reads `enabled`");
        assertTrue(info.containsKey("certificateHash"), "JS client reads `certificateHash` when enabled");
    }
}
