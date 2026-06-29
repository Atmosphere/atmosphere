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
package org.atmosphere.samples.springboot.a2a;

import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.transport.A2aAgentTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for the OUTBOUND A2A capability the Atmosphere&nbsp;4 blog
 * advertises: <em>"an Atmosphere agent can call other A2A agents over
 * JSON-RPC."</em>
 *
 * <p>This boots the sample's <b>own</b> A2A server — the real
 * {@link WeatherTimeAgent}, registered by the {@code AgentProcessor} at
 * {@code @Agent(endpoint = "/atmosphere/a2a")} and served by the Atmosphere
 * servlet (mapped to {@code /atmosphere/*}) — on a random port, then makes a
 * genuine OUTBOUND JSON-RPC 2.0 call to it through the production
 * {@link A2aAgentTransport} (the calling-side code the blog claim rests on).
 * The whole round trip runs over a real loopback socket: outbound transport
 * &rarr; HTTP &rarr; inbound protocol handler &rarr; skill &rarr; artifact
 * &rarr; back through the transport into an {@link AgentResult}.</p>
 *
 * <p>The assertions check the <em>observable response content produced by the
 * remote agent</em>, not that a transport object exists:</p>
 * <ul>
 *   <li>{@code isAvailable()} round-trips a {@code GetExtendedAgentCard} call;</li>
 *   <li>a {@code get-time} call comes back with the remote skill's artifact,
 *       echoing the timezone we sent — and a <em>different</em> timezone yields
 *       a <em>different</em> remote reply, proving the content is computed by
 *       the remote agent from our input rather than fabricated locally;</li>
 *   <li>an {@code ask} call comes back with a greeting string only the remote
 *       handler emits;</li>
 *   <li>an unknown skill surfaces as a failed {@link AgentResult} carrying the
 *       remote agent's reason (terminal-path correctness, not a fake success).</li>
 * </ul>
 *
 * <p>Fully offline and key-free: {@code llm.api-key=} is forced empty so the
 * agent takes its deterministic demo path (no network, no LLM), making every
 * remote reply a stable string.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.admin.enabled=false",
                // Force the deterministic, offline demo path: with no LLM key the
                // agent returns fixed strings we can assert on byte-for-byte.
                "llm.api-key="
        })
class A2aOutboundDeliveryTest {

    @LocalServerPort
    int port;

    /** The sample's own, really-booted A2A JSON-RPC endpoint. */
    private String a2aEndpoint() {
        return "http://localhost:" + port + "/atmosphere/a2a";
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void outboundCallReachesRemoteAgentAndReturnsItsResponse() {
        // A2aAgentTransport owns its HttpClient; we created it, so we close it.
        try (var transport = new A2aAgentTransport("atmosphere-assistant", a2aEndpoint())) {

            // 0. The remote agent is reachable: GetExtendedAgentCard round-trips.
            assertTrue(transport.isAvailable(),
                    "remote A2A agent must answer GetExtendedAgentCard at " + a2aEndpoint());

            // 1. Drive a deterministic skill OUTBOUND over JSON-RPC. get-time is
            //    pure date math (no LLM), so its artifact is stable and the
            //    timezone we send is echoed straight back by the REMOTE skill.
            AgentResult ny = transport.send("atmosphere-assistant", "get-time",
                    Map.of("timezone", "America/New_York"));
            assertTrue(ny.success(),
                    "outbound get-time call must succeed; got: " + ny.text());
            assertTrue(ny.text().contains("Current time in America/New_York"),
                    "reply must be the remote get-time artifact echoing our arg; got: " + ny.text());

            // 2. A different OUTBOUND arg yields a different REMOTE reply — proves
            //    the content is produced by the remote agent from our input, not a
            //    canned local value the transport could have invented.
            AgentResult tokyo = transport.send("atmosphere-assistant", "get-time",
                    Map.of("timezone", "Asia/Tokyo"));
            assertTrue(tokyo.success(),
                    "second outbound call must succeed; got: " + tokyo.text());
            assertTrue(tokyo.text().contains("Current time in Asia/Tokyo"),
                    "reply must echo the second timezone; got: " + tokyo.text());
            assertNotEquals(ny.text(), tokyo.text(),
                    "distinct timezones must yield distinct remote replies");

            // 3. A non-time skill also round-trips. On the forced demo path the
            //    'ask' skill returns a greeting that names the remote agent — a
            //    string only the REMOTE handler produces.
            AgentResult ask = transport.send("atmosphere-assistant", "ask",
                    Map.of("message", "hello"));
            assertTrue(ask.success(),
                    "outbound ask call must succeed; got: " + ask.text());
            assertTrue(ask.text().contains("Atmosphere A2A assistant agent"),
                    "reply must be the remote agent's demo greeting; got: " + ask.text());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void outboundCallToUnknownSkillPropagatesRemoteFailure() {
        try (var transport = new A2aAgentTransport("atmosphere-assistant", a2aEndpoint())) {
            AgentResult result = transport.send("atmosphere-assistant", "no-such-skill",
                    Map.of("q", "x"));
            assertFalse(result.success(),
                    "an unknown remote skill must surface as a failed AgentResult, "
                            + "not a fabricated success; got: " + result.text());
            assertTrue(result.text().contains("Unknown skill"),
                    "failure must carry the remote agent's reason; got: " + result.text());
        }
    }
}
