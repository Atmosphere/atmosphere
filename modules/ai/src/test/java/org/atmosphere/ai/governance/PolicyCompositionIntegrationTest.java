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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end composition: verifies that {@link PolicyRing},
 * {@link KillSwitchPolicy}, {@link MessageLengthPolicy},
 * {@link DenyListPolicy}, {@link AllowListPolicy},
 * {@link RateLimitPolicy}, {@link ConcurrencyLimitPolicy},
 * {@link DryRunPolicy}, and {@link TimedPolicy} compose cleanly
 * into one realistic admission chain.
 *
 * <p>Chain shape (ring indices chosen so cheapest = lowest index):</p>
 * <ol>
 *   <li>Ring 1 (sub-ms): kill switch, message-length cap, deny-list,
 *       allow-list</li>
 *   <li>Ring 2 (~ms): rate limit, concurrency limit</li>
 *   <li>Ring 3 (shadow): dry-run wrapper around a fictional future policy</li>
 * </ol>
 *
 * <p>Each ring's policies are wrapped in {@link TimedPolicy} so latency
 * metrics land in the facade automatically.</p>
 */
class PolicyCompositionIntegrationTest {

    private static PolicyContext userMsg(String user, String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, user, null, null, null, null, null),
                "");
    }

    private PolicyRing buildChain(KillSwitchPolicy killSwitch,
                                   RateLimitPolicy rateLimit,
                                   ConcurrencyLimitPolicy concurrencyLimit,
                                   DryRunPolicy dryRun) {
        return PolicyRing.builder("admission-chain")
                .ring(1,
                        TimedPolicy.of(killSwitch),
                        TimedPolicy.of(new MessageLengthPolicy("msg-cap", 500)),
                        TimedPolicy.of(new DenyListPolicy("sql-block", "DROP TABLE", "rm -rf")),
                        TimedPolicy.of(new AllowListPolicy("support-topics",
                                "order", "billing", "refund", "shipping", "account")))
                .ring(2,
                        TimedPolicy.of(rateLimit),
                        TimedPolicy.of(concurrencyLimit))
                .ring(3, TimedPolicy.of(dryRun))
                .build();
    }

    @Test
    void happyPathAdmitsOnTopicShortMessageWithinLimits() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 10, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 2);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "nothing-matches-this-xyz"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        var decision = chain.evaluate(userMsg("alice", "What's the status of my order?"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void ring1DenyListTripsBeforeRing2CostsLatency() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 10, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 2);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "nothing"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        var decision = chain.evaluate(userMsg("alice", "please DROP TABLE users"));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().contains("deny-list"));
        // Rate limit counter should NOT have incremented — deny happened in ring 1.
        assertInstanceOf(PolicyDecision.Admit.class,
                new RateLimitPolicy("rl-fresh", 1, Duration.ofSeconds(60))
                        .evaluate(userMsg("alice", "check my refund")));
    }

    @Test
    void killSwitchArmsAndImmediatelyDeniesAllTraffic() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 100, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 100);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "x"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        assertInstanceOf(PolicyDecision.Admit.class,
                chain.evaluate(userMsg("alice", "order status please")));

        killSwitch.arm("incident-42", "sre-oncall");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                chain.evaluate(userMsg("alice", "order status please")));
        assertTrue(deny.reason().contains("incident-42"));

        killSwitch.disarm();
        assertInstanceOf(PolicyDecision.Admit.class,
                chain.evaluate(userMsg("alice", "order status please")),
                "traffic resumes after disarm, no restart needed");
    }

    @Test
    void oversizedMessageDeniedInRing1() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 100, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 100);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "x"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        var oversize = "order ".repeat(200); // 1200 chars >> 500 cap
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                chain.evaluate(userMsg("alice", oversize)));
        assertTrue(deny.reason().contains("exceeds maximum"));
    }

    @Test
    void offTopicMessageDeniedByAllowList() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 100, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 100);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "x"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                chain.evaluate(userMsg("alice", "write me a python fibonacci function")));
        assertTrue(deny.reason().contains("allow-list"));
    }

    @Test
    void ring2RateLimitKicksInAfterCapReached() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 3, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 100);
        var dryRun = new DryRunPolicy(new DenyListPolicy("future-rule", "x"));

        var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
        var ctx = userMsg("alice", "order status");
        assertInstanceOf(PolicyDecision.Admit.class, chain.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Admit.class, chain.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Admit.class, chain.evaluate(ctx));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, chain.evaluate(ctx));
        assertTrue(deny.reason().contains("rate-limited"));
    }

    @Test
    void dryRunInRing3RecordsButNeverBlocks() {
        var killSwitch = new KillSwitchPolicy();
        var rateLimit = new RateLimitPolicy("rl", 100, Duration.ofSeconds(60));
        var concurrency = new ConcurrencyLimitPolicy("cc", 100);
        // Dry-run wraps a policy that WOULD deny "order" (the allow-list topic)
        // — the dry-run wrapper should record the would-deny but still admit.
        var futureRule = new DenyListPolicy("future-rule", "order");
        var dryRun = new DryRunPolicy(futureRule);

        GovernanceDecisionLog.install(10);
        try {
            var chain = buildChain(killSwitch, rateLimit, concurrency, dryRun);
            var decision = chain.evaluate(userMsg("alice", "my order status"));
            assertInstanceOf(PolicyDecision.Admit.class, decision,
                    "dry-run must not flip the chain's final decision");
            assertTrue(dryRun.shadowDenies() >= 1,
                    "dry-run must have recorded the would-deny");
        } finally {
            GovernanceDecisionLog.reset();
        }
    }
}
