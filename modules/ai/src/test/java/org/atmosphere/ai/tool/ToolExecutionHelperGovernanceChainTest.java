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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.GovernancePolicyChain;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the governance bypass on the resource-free dispatch paths
 * (AiPipeline channel bridges, A2A, AG-UI, coordinator-local). Tool-call
 * admission used to run only when an {@code AtmosphereResource} was in the
 * injectables scope, so a tool the policy plane should deny executed unchecked
 * on every non-{@code @AiEndpoint} path. The pipeline now threads its effective
 * policy chain through the injectables as a {@link GovernancePolicyChain}, and
 * {@link ToolExecutionHelper} admits against it — parity with the resource path
 * (Correctness Invariant #6 Security, #7 Mode Parity).
 */
class ToolExecutionHelperGovernanceChainTest {

    @BeforeEach
    void installLog() {
        GovernanceDecisionLog.install(50);
    }

    @AfterEach
    void resetLog() {
        GovernanceDecisionLog.reset();
    }

    private static ToolDefinition deleteTool(AtomicBoolean fired) {
        return ToolDefinition.builder("delete_database", "drop a database")
                .parameter("table", "table name", "string", true)
                .executor(args -> {
                    fired.set(true);
                    return "deleted:" + args.get("table");
                })
                .build();
    }

    /** Deny any call to the tool named {@code delete_database}. */
    private static GovernancePolicyChain denyDeleteChain() {
        return new GovernancePolicyChain(List.of(new DenyToolPolicy("delete_database")));
    }

    @Test
    void chainInInjectablesDeniesToolBeforeExecution() {
        var fired = new AtomicBoolean();
        var tool = deleteTool(fired);
        Map<Class<?>, Object> injectables =
                Map.of(GovernancePolicyChain.class, denyDeleteChain());

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_database", tool, Map.of("table", "users"),
                new NoopSession(), neverCalledStrategy(), ToolApprovalPolicy.allowAll(),
                injectables);

        assertFalse(fired.get(),
                "Resource-free path must deny the tool before its executor runs");
        assertTrue(result.contains("denied by policy"), result);
        assertTrue(result.contains("delete-guard"), result);
    }

    @Test
    void chainDenialIsRecordedToDecisionLog() {
        var fired = new AtomicBoolean();
        var tool = deleteTool(fired);

        ToolExecutionHelper.executeWithApproval(
                "delete_database", tool, Map.of("table", "users"),
                new NoopSession(), neverCalledStrategy(), ToolApprovalPolicy.allowAll(),
                Map.of(GovernancePolicyChain.class, denyDeleteChain()));

        var recent = GovernanceDecisionLog.installed().recent(10);
        assertEquals(1, recent.size());
        assertEquals("deny", recent.get(0).decision());
        assertEquals("delete-guard", recent.get(0).policyName());
    }

    @Test
    void withoutChainTheSameToolExecutes() {
        // Proves the chain is what enforces: with no GovernancePolicyChain in
        // scope (and no AtmosphereResource), admission is implicit-admit and the
        // tool runs — the exact pre-fix behavior the chain now closes.
        var fired = new AtomicBoolean();
        var tool = deleteTool(fired);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_database", tool, Map.of("table", "users"),
                new NoopSession(), neverCalledStrategy(), ToolApprovalPolicy.allowAll(),
                Map.of());

        assertTrue(fired.get());
        assertTrue(result.contains("deleted:users"), result);
    }

    @Test
    void nonMatchingToolPassesThroughChain() {
        var fired = new AtomicBoolean();
        var tool = ToolDefinition.builder("list_rows", "readonly query")
                .parameter("table", "table name", "string", true)
                .executor(args -> {
                    fired.set(true);
                    return "ok";
                })
                .build();

        var result = ToolExecutionHelper.executeWithApproval(
                "list_rows", tool, Map.of("table", "users"),
                new NoopSession(), neverCalledStrategy(), ToolApprovalPolicy.allowAll(),
                Map.of(GovernancePolicyChain.class, denyDeleteChain()));

        assertTrue(fired.get(), "A tool the chain does not target must still execute");
        assertTrue(result.contains("ok"), result);
    }

    private static ApprovalStrategy neverCalledStrategy() {
        return (approval, session) -> ApprovalStrategy.ApprovalOutcome.APPROVED;
    }

    /** Denies any tool whose name equals the configured target. */
    private record DenyToolPolicy(String deniedTool) implements GovernancePolicy {
        @Override public String name() {
            return "delete-guard";
        }

        @Override public String source() {
            return "code:test";
        }

        @Override public String version() {
            return "test";
        }

        @Override public PolicyDecision evaluate(PolicyContext context) {
            var meta = context.request() != null ? context.request().metadata() : Map.of();
            if (deniedTool.equals(meta.get("tool_name"))) {
                return PolicyDecision.deny("tool " + deniedTool + " is not allowed");
            }
            return PolicyDecision.admit();
        }
    }

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() {
            return "chain-test";
        }

        @Override public void send(String text) {
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
        }

        @Override public void complete(String summary) {
        }

        @Override public void error(Throwable t) {
        }

        @Override public boolean isClosed() {
            return false;
        }
    }
}
