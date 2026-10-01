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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.decision.DecisionModelResolverTestAccess;
import org.atmosphere.ai.decision.RuntimeDecisionModel;
import org.atmosphere.ai.decision.ScriptedDecisionRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every terminal path of the LLM moderation detector, driven through the real
 * {@link ModerationGuardrail}: a verdict the detector could not obtain is an
 * error, which the guardrail blocks by default — never a clean result.
 */
class LlmModerationDetectorFailClosedTest {

    private static final AiRequest REQUEST = new AiRequest("text the phrase rules do not match");

    @Test
    void emptyReplyBlocks() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.complete());
        assertBlocked(new LlmModerationDetector(runtime), "an empty reply");
    }

    @Test
    void unparseableReplyBlocks() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) ->
                ScriptedDecisionRuntime.reply(s, "I cannot help with that"));
        assertBlocked(new LlmModerationDetector(runtime), "an unparseable reply");
    }

    @Test
    void timeoutBlocks() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> new CountDownLatch(1).await());
        assertBlocked(new LlmModerationDetector(runtime, Duration.ofMillis(200)), "a timeout");
    }

    @Test
    void errorReportedOnTheSessionBlocks() {
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> s.error(new IllegalStateException("model 503")));
        assertBlocked(new LlmModerationDetector(runtime), "a runtime error reported on the session");
    }

    @Test
    void errorThrownByTheRuntimeBlocks() {
        assertBlocked(new LlmModerationDetector(new ThrowingRuntime()), "a runtime error thrown on dispatch");
    }

    /**
     * Only the demo runtime installed (no reachable model): the demo's canned
     * reply is not a verdict.
     */
    @Test
    void noReachableModelBlocks() {
        DecisionModelResolverTestAccess.forceDemoOnly();
        try {
            assertBlocked(new LlmModerationDetector(), "no reachable model");
        } finally {
            DecisionModelResolverTestAccess.restore();
        }
    }

    /**
     * Concurrent inspections share one decision model's concurrency bound,
     * and one inspection asks six questions. While another turn holds six
     * slots, a benign turn on a six-slot model gets no slot before its deadline
     * and is blocked (CAPACITY fails closed); on a model sized for two
     * inspections the same turn is admitted.
     */
    @Test
    void aSaturatedDecisionModelBlocksABenignTurnAndASizedOneAdmitsIt() throws Exception {
        var saturated = inspectWhileAnotherTurnHoldsSixSlots(ModerationCategory.values().length);
        var blocked = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, saturated,
                "a benign turn with no decision slot must fail closed");
        assertTrue(blocked.reason().contains("capacity"), blocked.reason());

        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                inspectWhileAnotherTurnHoldsSixSlots(2 * ModerationCategory.values().length),
                "a model sized for the fan-out admits the benign turn");
    }

    private static AiGuardrail.GuardrailResult inspectWhileAnotherTurnHoldsSixSlots(int maxConcurrency)
            throws Exception {
        var categories = ModerationCategory.values().length;
        var entered = new CountDownLatch(categories);
        var release = new CountDownLatch(1);
        var runtime = new ScriptedDecisionRuntime((ctx, s) -> {
            if (ctx.message().contains("HOLD")) {
                entered.countDown();
                release.await();
            }
            ScriptedDecisionRuntime.reply(s, "{\"answer\":false,\"confidence\":0.97}");
        });
        var model = new RuntimeDecisionModel(runtime, maxConcurrency, RuntimeDecisionModel.DEFAULT_MIN_OBSERVED_MASS);
        var holder = new ModerationGuardrail(new LlmModerationDetector(model, Duration.ofSeconds(30), null));
        var held = new CompletableFuture<AiGuardrail.GuardrailResult>();
        Thread.ofVirtual().start(() -> held.complete(holder.inspectRequest(new AiRequest("HOLD the line"))));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the first turn holds one slot per category");
            return new ModerationGuardrail(new LlmModerationDetector(model, Duration.ofMillis(300), null))
                    .inspectRequest(new AiRequest("what time does the store open?"));
        } finally {
            release.countDown();
            assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, held.get(10, TimeUnit.SECONDS),
                    "the held turn completes and is admitted once released");
        }
    }

    private static void assertBlocked(LlmModerationDetector detector, String path) {
        var result = detector.detect(REQUEST.message());
        assertTrue(result.errored(), path + " must be an errored result, got " + result);
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                new ModerationGuardrail(detector).inspectRequest(REQUEST),
                path + " must block the turn");
    }

    /** Throws from the dispatch itself instead of reporting on the session. */
    private static final class ThrowingRuntime implements AgentRuntime {
        @Override public String name() { return "throwing"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new IllegalStateException("model 503");
        }
    }
}
