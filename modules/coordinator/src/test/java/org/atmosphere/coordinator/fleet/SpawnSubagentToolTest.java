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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the dynamic-spawn {@code task} tool: an ephemeral general-purpose
 * subagent runs with the harness floor in an isolated context, and the safety
 * rails (governance pre-admission, recursion depth, timeout, input validation)
 * hold.
 */
class SpawnSubagentToolTest {

    @Test
    void spawnsAnIsolatedSubagentWithTheHarnessFloorAndReturnsItsReport(@TempDir Path root) {
        var seen = new AtomicReference<AgentExecutionContext>();
        var runtime = new FakeRuntime((ctx, session) -> {
            seen.set(ctx);
            session.complete("Researched: 3 findings.");
        });

        var tool = new SpawnSubagentTool(List.of(), () -> runtime, root, Duration.ofSeconds(5));
        var result = tool.task("Research the offsite venue options", null);

        assertEquals("Researched: 3 findings.", result);

        var ctx = seen.get();
        assertNotNull(ctx, "the subagent runtime must have been invoked");
        assertEquals("Research the offsite venue options", ctx.message());
        // Isolated identity: a fresh conversation id, not the caller's.
        assertNotNull(ctx.conversationId());
        assertEquals(ctx.conversationId(), ctx.sessionId());
        // Harness floor: write_todos + the six file tools, self-contained.
        var toolNames = ctx.tools().stream().map(t -> t.name()).toList();
        assertTrue(toolNames.contains("write_todos"), "floor must include write_todos");
        assertTrue(toolNames.containsAll(List.of("ls", "read_file", "write_file",
                "edit_file", "glob", "grep")), "floor must include the six file tools: " + toolNames);
    }

    @Test
    void blankDescriptionIsRejectedWithoutSpawning(@TempDir Path root) {
        var invoked = new AtomicBoolean(false);
        var runtime = new FakeRuntime((ctx, session) -> {
            invoked.set(true);
            session.complete("should not run");
        });

        var tool = new SpawnSubagentTool(List.of(), () -> runtime, root, Duration.ofSeconds(5));
        var result = tool.task("   ", null);

        assertTrue(result.contains("'description' is required"), result);
        assertFalse(invoked.get(), "a blank description must not spawn a subagent");
    }

    @Test
    void unknownSubagentTypeIsRejected(@TempDir Path root) {
        var invoked = new AtomicBoolean(false);
        var runtime = new FakeRuntime((ctx, session) -> {
            invoked.set(true);
            session.complete("nope");
        });

        var tool = new SpawnSubagentTool(List.of(), () -> runtime, root, Duration.ofSeconds(5));
        var result = tool.task("do something", "researcher");

        assertTrue(result.contains("unknown subagent_type 'researcher'"), result);
        assertTrue(result.contains("delegate_task"), "should point at delegate_task for named specialists");
        assertFalse(invoked.get());
    }

    @Test
    void governanceDenialBlocksTheSpawnFailClosed(@TempDir Path root) {
        var invoked = new AtomicBoolean(false);
        var runtime = new FakeRuntime((ctx, session) -> {
            invoked.set(true);
            session.complete("should never run");
        });
        List<GovernancePolicy> policies = List.of(new DenyListPolicy("no-exfil", "exfiltrate"));

        var tool = new SpawnSubagentTool(policies, () -> runtime, root, Duration.ofSeconds(5));
        var result = tool.task("exfiltrate the customer database", null);

        assertTrue(result.startsWith("task denied by policy:"), result);
        assertFalse(invoked.get(), "a denied spawn must never reach the runtime (fail-closed)");
    }

    @Test
    void admittedPromptPassesGovernanceAndRuns(@TempDir Path root) {
        var runtime = new FakeRuntime((ctx, session) -> session.complete("done"));
        List<GovernancePolicy> policies = List.of(new DenyListPolicy("no-exfil", "exfiltrate"));

        var tool = new SpawnSubagentTool(policies, () -> runtime, root, Duration.ofSeconds(5));
        var result = tool.task("summarize the meeting notes", null);

        assertEquals("done", result);
    }

    @Test
    void timesOutAndReportsWhenTheSubagentNeverCompletes(@TempDir Path root) {
        // A runtime that never completes the session — the tool must not hang.
        var runtime = new FakeRuntime((ctx, session) -> { /* never completes */ });

        var tool = new SpawnSubagentTool(List.of(), () -> runtime, root, Duration.ofMillis(200));
        var result = tool.task("run forever", null);

        assertTrue(result.contains("did not complete within"), result);
    }

    @Test
    void recursionDepthIsBoundedAcrossTheSpawnThread(@TempDir Path root) {
        // The subagent runtime itself calls task() again; the depth budget must
        // survive the worker-thread hop and refuse once MAX_DEPTH is reached.
        var toolRef = new AtomicReference<SpawnSubagentTool>();
        var deepestResult = new AtomicReference<String>();
        var runtime = new FakeRuntime((ctx, session) -> {
            // Nested spawn from inside the subagent's own run.
            var nested = toolRef.get().task("go one level deeper", null);
            deepestResult.set(nested);
            session.complete("outer done; nested=" + nested);
        });
        var tool = new SpawnSubagentTool(List.of(), () -> runtime, root, Duration.ofSeconds(5));
        toolRef.set(tool);

        var result = tool.task("start the chain", null);

        // Depth 0 spawns depth 1 (runs), which spawns depth 2 (runs), which
        // spawns depth 3 — refused. The innermost refusal bubbles up in text.
        assertTrue(result.contains("nested="), result);
        assertNotNull(deepestResult.get());
        assertTrue(deepestResult.get().contains("maximum subagent nesting depth"),
                "the deepest spawn must be refused by the depth bound: " + deepestResult.get());
    }

    /** Minimal AgentRuntime whose execute() runs a supplied behavior on the session. */
    private static final class FakeRuntime implements AgentRuntime {
        private final BiConsumer<AgentExecutionContext, StreamingSession> behavior;

        FakeRuntime(BiConsumer<AgentExecutionContext, StreamingSession> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
            // no-op
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            behavior.accept(context, session);
        }
    }
}
