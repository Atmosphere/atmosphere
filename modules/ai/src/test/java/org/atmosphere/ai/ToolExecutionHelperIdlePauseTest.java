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
package org.atmosphere.ai;

import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.GovernancePolicyChain;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.resume.DurableRunContext;
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.atmosphere.ai.tool.ToolAuthorization;
import org.atmosphere.ai.tool.ToolAuthorizationRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the stream idle pause around {@link ToolExecutionHelper}'s tool
 * execution: on every path a dispatched call takes (a slow tool, an approval
 * parked longer than the bound, a governance deny, an {@code @Authorize} deny,
 * a throw, a replay hit) the turn's clock is paused while the call runs and
 * resumes, with a full window, once it returns. Each case reads the pause depth
 * from inside the path, so "depth back to 0" cannot pass without a pause.
 */
class ToolExecutionHelperIdlePauseTest {

    private static final Duration BOUND = Duration.ofMinutes(5);
    private static final long BOUND_NANOS = BOUND.toNanos();
    private static final String RUN_ID = "idle-run";

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Integer> depthsSeen = new CopyOnWriteArrayList<>();
    private StreamIdleWatchdogTest.RecordingSession delegate;
    private StreamIdleWatchdog watchdog;

    @BeforeEach
    void open() {
        delegate = new StreamIdleWatchdogTest.RecordingSession() {
            @Override
            public void emit(AiEvent event) {
                if (event instanceof AiEvent.ToolStart) {
                    depthsSeen.add(watchdog.pauseDepth());
                }
                super.emit(event);
            }
        };
        watchdog = new StreamIdleWatchdog(BOUND, delegate, "test", now::get,
                Duration.ofMillis(10), Duration.ofSeconds(30), (name, task) -> null);
        GovernanceDecisionLog.install(50);
    }

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
        ToolAuthorizationRegistry.clear();
        GovernanceDecisionLog.reset();
    }

    /** Lets more than the bound pass, then has the clock look: it must not fire. */
    private void outlastTheBound() {
        depthsSeen.add(watchdog.pauseDepth());
        now.addAndGet(3 * BOUND_NANOS);
        assertTrue(watchdog.check() > 0, "the clock fired while a tool call was running");
    }

    /** After the call: unpaused, a full window from its end, and still able to fire. */
    private void assertResumed() {
        assertEquals(0, watchdog.pauseDepth(), "the pause was left open");
        assertFalse(watchdog.timedOut());
        assertTrue(watchdog.check() > 0, "closing the pause restarts the full window");
        now.addAndGet(BOUND_NANOS + 1);
        assertTrue(watchdog.check() < 0, "the clock runs again after the call");
        assertTrue(watchdog.timedOut());
    }

    private String call(ToolDefinition tool, ApprovalStrategy strategy, Map<Class<?>, Object> injectables) {
        return ToolExecutionHelper.executeWithApproval(tool.name(), tool, Map.of(),
                watchdog.session(), strategy, ToolApprovalPolicy.annotated(), injectables);
    }

    @Test
    void aSlowToolIsNotIdle() {
        var tool = ToolDefinition.builder("slow", "takes longer than the bound")
                .executor(args -> {
                    outlastTheBound();
                    return "done";
                })
                .build();

        assertEquals("done", call(tool, null, Map.of()));

        assertTrue(depthsSeen.contains(1) && !depthsSeen.contains(0), "depths seen: " + depthsSeen);
        assertResumed();
    }

    @Test
    void anApprovalParkedForThreeTimesTheBoundIsNotIdle() {
        var tool = ToolDefinition.builder("sensitive", "needs approval")
                .executor(args -> "ran")
                .requiresApproval("Approve?", 3600)
                .build();
        ApprovalStrategy strategy = (approval, session) -> {
            outlastTheBound();
            return ApprovalStrategy.ApprovalOutcome.APPROVED;
        };

        assertEquals("ran", call(tool, strategy, Map.of()));

        assertTrue(depthsSeen.size() >= 2 && !depthsSeen.contains(0), "depths seen: " + depthsSeen);
        assertResumed();
    }

    @Test
    void aGovernanceDenyClosesThePause() {
        var tool = ToolDefinition.builder("delete_database", "drop it")
                .executor(args -> "deleted")
                .build();
        GovernancePolicy deny = new GovernancePolicy() {
            @Override public String name() { return "deny-all"; }
            @Override public String source() { return "code:test"; }
            @Override public String version() { return "test"; }
            @Override public PolicyDecision evaluate(PolicyContext context) {
                outlastTheBound();
                return PolicyDecision.deny("not allowed");
            }
        };

        var result = call(tool, null, Map.of(GovernancePolicyChain.class,
                new GovernancePolicyChain(List.of(deny))));

        assertTrue(result.contains("denied by policy"), result);
        assertEquals(List.of(1), depthsSeen);
        assertResumed();
    }

    @Test
    void anAuthorizeDenyClosesThePause() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user", new ToolAuthorization(Set.of("admin"), Set.of()));
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userRoles")).thenAnswer(invocation -> {
            outlastTheBound();
            return Set.of("guest");
        });

        var result = call(tool, null, Map.of(AtmosphereResource.class, resource));

        assertTrue(result.contains("insufficient authorization"), result);
        assertTrue(!depthsSeen.isEmpty() && !depthsSeen.contains(0), "depths seen: " + depthsSeen);
        assertResumed();
    }

    @Test
    void aThrowClosesThePause() {
        var tool = ToolDefinition.builder("sensitive", "needs approval")
                .executor(args -> "ran")
                .requiresApproval("Approve?", 3600)
                .build();
        ApprovalStrategy strategy = (approval, session) -> {
            outlastTheBound();
            throw new IllegalStateException("approval store down");
        };

        assertThrows(IllegalStateException.class, () -> call(tool, strategy, Map.of()));

        assertTrue(!depthsSeen.isEmpty() && !depthsSeen.contains(0), "depths seen: " + depthsSeen);
        assertResumed();
    }

    @Test
    void aReplayHitClosesThePause() {
        var tool = ToolDefinition.builder("echo", "echo")
                .executor(args -> {
                    throw new AssertionError("a replay hit must not run the tool");
                })
                .build();
        var journal = new InMemoryEffectJournal();
        var key = EffectKeys.toolCall(RUN_ID, "echo", Map.of(), 0);
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL, key,
                EffectKeys.sha256Hex("echo", EffectKeys.canonicalJson(Map.of()), "owner"));
        journal.commit(RUN_ID, key, "recorded");
        DurableRunScopeHolder.install(RUN_ID, new DurableRunContext(RUN_ID, journal, true, "owner", "owner"));
        delegate.runId = RUN_ID;

        assertEquals("recorded", call(tool, null, Map.of()));

        assertEquals(List.of(1), depthsSeen, "the replayed ToolStart was written while paused");
        assertResumed();
    }

    @Test
    void concurrentToolsOnOtherThreadsArePausedPerSessionNotPerThread() throws Exception {
        var running = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var tool = ToolDefinition.builder("parallel", "runs alongside another")
                .executor(args -> {
                    running.countDown();
                    try {
                        assertTrue(release.await(20, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                })
                .build();
        var first = Thread.ofVirtual().start(() -> call(tool, null, Map.of()));
        var second = Thread.ofVirtual().start(() -> call(tool, null, Map.of()));
        assertTrue(running.await(20, TimeUnit.SECONDS));

        assertEquals(2, watchdog.pauseDepth(), "each running call holds its own pause");
        now.addAndGet(3 * BOUND_NANOS);
        assertTrue(watchdog.check() > 0);

        release.countDown();
        assertTrue(first.join(Duration.ofSeconds(20)));
        assertTrue(second.join(Duration.ofSeconds(20)));
        depthsSeen.clear();
        assertResumed();
    }

    @Test
    void aCallOnAnEndedTurnTakesNoPause() {
        var tool = ToolDefinition.builder("late", "after the end")
                .executor(args -> {
                    throw new AssertionError("must not run");
                })
                .build();
        watchdog.session().complete();
        now.addAndGet(BOUND_NANOS);
        var lastActivity = watchdog.lastActivityNanos();

        assertEquals(ToolExecutionHelper.TURN_ENDED_RESULT, call(tool, null, Map.of()));
        assertEquals(0, watchdog.pauseDepth());
        assertEquals(lastActivity, watchdog.lastActivityNanos(),
                "no pause was opened (closing one records activity)");
    }
}
