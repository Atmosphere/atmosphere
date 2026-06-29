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
package org.atmosphere.samples.springboot.a2astartup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.atmosphere.spring.boot.webtransport.DefaultWebTransportProcessor;
import org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer;
import org.atmosphere.spring.boot.webtransport.WebTransportInfoController;
import org.atmosphere.webtransport.WebTransportProcessor;
import org.atmosphere.webtransport.WebTransportProcessorAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that proves the WebTransport-over-HTTP/3 transport advertised
 * in this sample's README (and in the Atmosphere 4 blog) is genuinely wired into
 * the running application — not merely present as classes on the classpath.
 *
 * <p>The sample's {@code application.yml} already enables WebTransport
 * ({@code atmosphere.web-transport.enabled=true}) on fixed UDP port 4446. This
 * test boots the real {@link A2aStartupTeamApplication} context, overriding only
 * the bind target to an ephemeral UDP port so the test never clashes with a
 * running sample (or a parallel test), and asserts the observable wiring at the
 * strongest level feasible without a browser QUIC client:</p>
 *
 * <ol>
 *   <li>the HTTP/3 auto-configuration beans are published in the real context —
 *       i.e. {@code @ConditionalOnClass(Http3)} matched on this sample's
 *       classpath and the enable gate fired;</li>
 *   <li>the in-JVM Reactor Netty HTTP/3 server actually bound a UDP port at
 *       runtime (a real QUIC bind, not a config flag);</li>
 *   <li>the {@code /api/webtransport-info} discovery endpoint — over a real HTTP
 *       round-trip through the booted servlet container — advertises
 *       runtime-confirmed state only (Invariant #5: Runtime Truth);</li>
 *   <li>the {@code Alt-Svc} advertisement filter is installed in the real servlet
 *       chain while the server runs;</li>
 *   <li>the {@code WebTransportProcessor} SPI resolves the starter's real
 *       {@link DefaultWebTransportProcessor} (which bridges into the WebSocket
 *       processor), not the no-op {@link WebTransportProcessorAdapter} fallback.</li>
 * </ol>
 *
 * <p><strong>Honest scope:</strong> this test does not perform a true HTTP/3
 * WebTransport data-plane round-trip (QUIC handshake -&gt; extended CONNECT -&gt; bidi
 * stream echo) — that requires a real Chrome with HTTP/3 and is exercised by the
 * Playwright specs under {@code modules/integration-tests/e2e/webtransport*.spec.ts}.
 * This test proves the control plane and the UDP bind are genuinely wired in the
 * sample.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.admin.enabled=false",
                // web-transport.enabled=true comes from the sample's application.yml;
                // override only the bind target to an ephemeral UDP port for isolation.
                "atmosphere.web-transport.host=127.0.0.1",
                "atmosphere.web-transport.port=0"
        })
class WebTransportWiringE2ETest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AtmosphereFramework framework;

    @LocalServerPort
    private int httpPort;

    @Test
    void autoConfigPublishesWebTransportBeansInTheRealSampleContext() {
        // Beans only exist when @ConditionalOnClass({HttpServer, Http3}) matched on
        // THIS sample's classpath AND atmosphere.web-transport.enabled fired —
        // i.e. the transport is wired into the running app, not just on the classpath.
        assertTrue(context.getBeanNamesForType(ReactorNettyTransportServer.class).length > 0,
                "ReactorNettyTransportServer bean must be published when web-transport is enabled");
        assertTrue(context.getBeanNamesForType(WebTransportInfoController.class).length > 0,
                "WebTransportInfoController bean must be published for client certificate discovery");
    }

    @Test
    void http3ServerActuallyBindsAUdpPortAtRuntime() {
        var server = context.getBean(ReactorNettyTransportServer.class);
        assertTrue(server.isRunning(),
                "SmartLifecycle must have started the in-JVM HTTP/3 server (Invariant #5: runtime truth)");
        assertTrue(server.port() > 0,
                "server.port() must report the kernel-assigned UDP port, proving a real QUIC bind happened");
    }

    @Test
    void infoEndpointAdvertisesRuntimeConfirmedStateOverRealHttp() throws Exception {
        var resp = getWebTransportInfo();
        assertEquals(200, resp.statusCode(), "discovery endpoint must be reachable");

        var body = mapper.readTree(resp.body());
        assertTrue(body.path("enabled").asBoolean(false),
                "endpoint must advertise enabled=true only because the server is actually running");
        assertTrue(body.path("port").asInt(-1) > 0,
                "endpoint must report the actual bound UDP port the JS client connects to");

        var hashNode = body.get("certificateHash");
        assertNotNull(hashNode, "running server must expose its self-signed cert hash for serverCertificateHashes");
        var hash = hashNode.asText();
        assertEquals(44, hash.length(),
                "JS client expects standard base64 SHA-256 (44 chars including '=' padding)");
        assertEquals(32, Base64.getDecoder().decode(hash).length,
                "the decoded certificate digest must be exactly 32 bytes (SHA-256)");
    }

    @Test
    void altSvcHeaderIsAdvertisedInTheRealServletChainWhileRunning() throws Exception {
        var resp = getWebTransportInfo();
        var altSvc = resp.headers().firstValue("Alt-Svc").orElse(null);
        assertNotNull(altSvc,
                "AltSvcFilter must be installed in the servlet chain and emit Alt-Svc while the H3 server runs");
        assertTrue(altSvc.startsWith("h3="),
                "Alt-Svc must advertise the HTTP/3 endpoint, got: " + altSvc);
        assertTrue(altSvc.contains("ma=86400"),
                "Alt-Svc must carry the advertised max-age, got: " + altSvc);
    }

    @Test
    void webTransportProcessorSpiResolvesTheStarterImplNotTheNoOpAdapter() {
        WebTransportProcessor processor = WebTransportProcessorFactory.getDefault()
                .getWebTransportProcessor(framework);

        assertInstanceOf(DefaultWebTransportProcessor.class, processor,
                "the SPI must resolve the starter's DefaultWebTransportProcessor (which bridges into the "
                        + "WebSocket processor), proving WebTransport is genuinely wired end-to-end on the "
                        + "sample classpath");
        assertFalse(processor instanceof WebTransportProcessorAdapter,
                "resolution must NOT fall back to the no-op WebTransportProcessorAdapter");
    }

    /** Real HTTP round-trip to the discovery endpoint over the booted servlet container. */
    private HttpResponse<String> getWebTransportInfo() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/api/webtransport-info"))
                .GET()
                .build();
        // HttpClient is AutoCloseable (JDK 21) — we create it here, so we close it.
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
