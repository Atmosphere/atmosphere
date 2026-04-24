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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterceptingAgentFleetTest {

    private AgentFleet buildFleet(AgentTransport transport) {
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("research", "web_search", "ok", Map.of(),
                        Duration.ofMillis(5), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("research",
                new DefaultAgentProxy("research", "1.0.0", 1, true, transport));
        return new DefaultAgentFleet(proxies);
    }

    @Test
    void proceedDispatchesToDelegate() {
        var transport = mock(AgentTransport.class);
        var fleet = buildFleet(transport)
                .withInterceptor(call -> FleetInterceptor.Decision.proceed());

        var result = fleet.agent("research").call("web_search", Map.of("q", "hi"));
        assertTrue(result.success());
        verify(transport, times(1)).send(any(), any(), any());
    }

    @Test
    void denySkipsTransportHopAndReturnsSyntheticFailure() {
        var transport = mock(AgentTransport.class);
        var fleet = buildFleet(transport)
                .withInterceptor(call -> FleetInterceptor.Decision.deny("off-scope skill"));

        var result = fleet.agent("research").call("write_code", Map.of("lang", "python"));
        assertFalse(result.success(),
                "fleet-interceptor deny must produce a failed AgentResult");
        assertTrue(result.text().contains("off-scope skill"));
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void rewriteForwardsModifiedCallToDelegate() {
        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        // Capture the args the transport actually sees so we prove the
        // rewrite made it through.
        var sentArgs = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        when(transport.send(any(), any(), any())).thenAnswer(inv -> {
            sentArgs.set(inv.getArgument(2));
            return new AgentResult("research", "web_search", "ok",
                    Map.of(), Duration.ofMillis(5), true);
        });
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("research",
                new DefaultAgentProxy("research", "1.0.0", 1, true, transport));
        var fleet = new DefaultAgentFleet(proxies)
                .withInterceptor(call -> {
                    var scrubbed = new LinkedHashMap<>(call.args());
                    scrubbed.put("q", "[redacted]");
                    return FleetInterceptor.Decision.rewrite(
                            new AgentCall(call.agentName(), call.skill(), scrubbed));
                });

        var result = fleet.agent("research").call("web_search", Map.of("q", "ssn 123-45-6789"));
        assertTrue(result.success());
        assertEquals("[redacted]", sentArgs.get().get("q"),
                "transport must observe the rewritten args, not the original");
    }

    @Test
    void chainShortCircuitsOnFirstDeny() {
        var transport = mock(AgentTransport.class);
        var firstRan = new AtomicInteger();
        var secondRan = new AtomicInteger();
        var fleet = buildFleet(transport)
                .withInterceptor(call -> {
                    firstRan.incrementAndGet();
                    return FleetInterceptor.Decision.deny("first blocks");
                })
                .withInterceptor(call -> {
                    secondRan.incrementAndGet();
                    return FleetInterceptor.Decision.proceed();
                });

        fleet.agent("research").call("web_search", Map.of());
        assertEquals(1, firstRan.get());
        assertEquals(0, secondRan.get(),
                "second interceptor must not run after first denies");
    }

    @Test
    void chainAppliesRewritesInOrder() {
        var transport = mock(AgentTransport.class);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("research", "web_search", "ok",
                        Map.of(), Duration.ofMillis(5), true));

        var seenBySecond = new AtomicInteger();
        var fleet = buildFleet(transport)
                .withInterceptor(call -> FleetInterceptor.Decision.rewrite(
                        new AgentCall(call.agentName(), call.skill(),
                                Map.of("stage", "first"))))
                .withInterceptor(call -> {
                    if ("first".equals(call.args().get("stage"))) {
                        seenBySecond.incrementAndGet();
                    }
                    return FleetInterceptor.Decision.rewrite(
                            new AgentCall(call.agentName(), call.skill(),
                                    Map.of("stage", "second")));
                });

        fleet.agent("research").call("web_search", Map.of("stage", "initial"));
        assertEquals(1, seenBySecond.get(),
                "second interceptor must observe the first interceptor's rewrite");
    }

    @Test
    void parallelDispatchHonoursInterceptorPerCall() {
        var transport = mock(AgentTransport.class);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("research", "web_search", "ok",
                        Map.of(), Duration.ofMillis(5), true));

        var fleet = buildFleet(transport)
                .withInterceptor(call -> "blocked".equals(call.args().get("flag"))
                        ? FleetInterceptor.Decision.deny("blocked by flag")
                        : FleetInterceptor.Decision.proceed());

        var results = fleet.parallel(
                new AgentCall("research", "web_search", Map.of("flag", "ok")),
                new AgentCall("research", "web_search", Map.of("flag", "blocked"))
        );
        // Same agent used twice — only one entry by name (last write wins),
        // but the deny is observable through the "research" text.
        assertTrue(results.containsKey("research"));
    }

    @Test
    void nullInterceptorRejected() {
        var fleet = buildFleet(mock(AgentTransport.class));
        assertThrows(IllegalArgumentException.class, () -> fleet.withInterceptor(null));
    }

    @Test
    void multipleInterceptorsComposeWithoutExtraWrapping() {
        var fleet = buildFleet(mock(AgentTransport.class));
        var wrapped1 = fleet.withInterceptor(call -> FleetInterceptor.Decision.proceed());
        var wrapped2 = wrapped1.withInterceptor(call -> FleetInterceptor.Decision.proceed());
        assertTrue(wrapped2 instanceof InterceptingAgentFleet,
                "chained calls reuse the outer InterceptingAgentFleet");
        assertEquals(2, ((InterceptingAgentFleet) wrapped2).interceptors().size());
    }
}
