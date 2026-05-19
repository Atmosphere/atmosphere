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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consensus dispatch on {@link AgentFleet#vote(AgentCall...)}: majority wins,
 * ties break by insertion order, all-fail collapses to a single failure
 * attributed to {@code "vote"}, and case/whitespace differences do not
 * split the vote.
 */
class AgentFleetVoteTest {

    @Test
    void voteReturnsMajorityResultText() {
        var fleet = newFleet(Map.of(
                "a", "yes",
                "b", "yes",
                "c", "no"));
        var result = fleet.vote(
                fleet.call("a", "decide", Map.of()),
                fleet.call("b", "decide", Map.of()),
                fleet.call("c", "decide", Map.of()));
        assertTrue(result.success());
        assertEquals("yes", result.text());
    }

    @Test
    void voteIgnoresWhitespaceAndCaseDifferences() {
        var fleet = newFleet(Map.of(
                "alpha", "YES",
                "beta", " yes ",
                "gamma", "no"));
        var result = fleet.vote(
                fleet.call("alpha", "skill", Map.of()),
                fleet.call("beta", "skill", Map.of()),
                fleet.call("gamma", "skill", Map.of()));
        assertTrue(result.success());
        // Returned text is the *original* response — the first one that
        // matched the winning normalised cohort, not the normalised form.
        assertEquals("YES", result.text());
    }

    @Test
    void voteTieBreaksByInsertionOrder() {
        var fleet = newFleet(Map.of(
                "first", "alpha",
                "second", "beta"));
        // 1 vs 1 tie — the first call's response wins.
        var result = fleet.vote(
                fleet.call("first", "skill", Map.of()),
                fleet.call("second", "skill", Map.of()));
        assertEquals("alpha", result.text());
    }

    @Test
    void voteReturnsSyntheticFailureWhenEveryPeerFails() {
        var fleet = newFailingFleet("flaky-one", "flaky-two");
        var result = fleet.vote(
                fleet.call("flaky-one", "skill", Map.of()),
                fleet.call("flaky-two", "skill", Map.of()));
        assertFalse(result.success());
        assertEquals("vote", result.agentName());
        assertTrue(result.text().contains("All 2 peer(s) failed"),
                "failure body should report the count, got: " + result.text());
    }

    @Test
    void voteWithEmptyCallsReturnsSyntheticFailure() {
        var fleet = newFleet(Map.of());
        var result = fleet.vote();
        assertFalse(result.success());
        assertEquals("vote", result.agentName());
    }

    @Test
    void voteOnSingleSurvivorPicksIt() {
        // One success, one failure: the survivor wins regardless of vote share.
        var fleet = newMixedFleet("good", "bad");
        var result = fleet.vote(
                fleet.call("good", "skill", Map.of()),
                fleet.call("bad", "skill", Map.of()));
        assertTrue(result.success());
        assertEquals("good", result.agentName());
    }

    private DefaultAgentFleet newFleet(Map<String, String> responses) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (var entry : responses.entrySet()) {
            proxies.put(entry.getKey(),
                    proxy(entry.getKey(), entry.getValue(), true));
        }
        return new DefaultAgentFleet(proxies);
    }

    private DefaultAgentFleet newFailingFleet(String... names) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        for (var name : names) {
            proxies.put(name, proxy(name, "boom", false));
        }
        return new DefaultAgentFleet(proxies);
    }

    private DefaultAgentFleet newMixedFleet(String goodName, String badName) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put(goodName, proxy(goodName, "answer", true));
        proxies.put(badName, proxy(badName, "fail", false));
        return new DefaultAgentFleet(proxies);
    }

    private AgentProxy proxy(String name, String text, boolean success) {
        AgentTransport transport = new AgentTransport() {
            @Override public boolean isAvailable() { return true; }
            @Override
            public AgentResult send(String agentName, String skillId, Map<String, Object> args) {
                return success
                        ? new AgentResult(agentName, skillId, text, Map.of(), Duration.ofMillis(1), true)
                        : AgentResult.failure(agentName, skillId, text, Duration.ofMillis(1));
            }
            @Override
            public void stream(String agentName, String skillId, Map<String, Object> args,
                               Consumer<String> onToken, Runnable onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        return new DefaultAgentProxy(name, "1.0.0", 1, true, transport);
    }
}
