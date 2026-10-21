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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.FileSystemTools;
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame that ends a tool call says whether the tool produced a result.
 * A call that failed (it threw, overran its deadline, got invalid arguments,
 * or its turn was over before it was dispatched) ends with a
 * {@code tool-error}, never a {@code tool-result}: the Console rendered those
 * as "✓ Done" for a tool that did not run. A refusal (an approval denied or
 * expired, a policy denial) still ends with the {@code {"status",…}}
 * {@code tool-result} the Console renders as denied or timed out. Whatever
 * the frame, the string the model receives is the one it always received.
 */
class ToolFailureFramesTest {

    private static final Map<String, Object> ARGS = Map.of("city", "Osaka");

    private static ToolDefinition tool(ToolExecutor executor) {
        return ToolDefinition.builder("get_weather", "Weather for a city")
                .parameter("city", "the city", "string")
                .executor(executor)
                .build();
    }

    @Test
    void aToolThatThrowsEndsWithAToolErrorAndTheModelGetsTheSameErrorJson() {
        var session = new CapturingSession();
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                tool(args -> {
                    throw new IllegalStateException("weather service down");
                }), ARGS, session, null);

        assertEquals("{\"error\":\"weather service down\"}", result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertEquals("weather service down", session.error());
    }

    @Test
    void aToolThatOverrunsItsDeadlineEndsWithAToolError() {
        var session = new CapturingSession();
        var slow = ToolDefinition.builder("get_weather", "Weather for a city")
                .parameter("city", "the city", "string")
                .executor(args -> {
                    Thread.sleep(10_000);
                    return "sunny";
                })
                .executionTimeout(1)
                .build();

        var result = ToolExecutionHelper.executeWithApproval("get_weather", slow, ARGS, session, null);

        assertTrue(result.startsWith("{\"error\":\"tool_timeout\""), result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertEquals("Tool execution exceeded the 1s deadline and was cancelled", session.error());
    }

    @Test
    void invalidArgumentsEndTheCallTheModelMadeWithAToolError() {
        var session = new CapturingSession();
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                tool(args -> "sunny"), Map.of(), session, null);

        assertTrue(result.startsWith("{\"error\":\"invalid_arguments\""), result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertTrue(session.error().startsWith("Invalid arguments: "), session.error());
    }

    @Test
    void aToolOfACancelledTurnIsNotRunAndEndsWithAToolError() {
        var session = new CapturingSession();
        session.cancelled = true;
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                tool(args -> {
                    throw new AssertionError("a cancelled turn's tool must not run");
                }), ARGS, session, null);

        assertEquals(ToolExecutionHelper.TURN_CANCELLED_RESULT, result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertEquals("The run was cancelled; the tool was not executed.", session.error());
    }

    @Test
    void aToolThatSucceedsEndsWithItsResult() {
        var session = new CapturingSession();
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                tool(args -> "sunny"), ARGS, session, null);

        assertEquals("sunny", result);
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames());
        assertEquals("sunny", ((AiEvent.ToolResult) session.events.get(1)).result());
    }

    @Test
    void anApprovalTheUserDeniedStaysARefusalResult() {
        var session = new CapturingSession();
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                gated(), ARGS, session, strategy(ApprovalStrategy.ApprovalOutcome.DENIED));

        assertEquals("{\"status\":\"cancelled\",\"message\":\"Action cancelled by user\"}", result);
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames());
        assertEquals(result, ((AiEvent.ToolResult) session.events.get(1)).result());
    }

    @Test
    void anApprovalThatTimedOutStaysARefusalResult() {
        var session = new CapturingSession();
        var result = ToolExecutionHelper.executeWithApproval("get_weather",
                gated(), ARGS, session, strategy(ApprovalStrategy.ApprovalOutcome.TIMED_OUT));

        assertEquals("{\"status\":\"timeout\",\"message\":\"Approval timed out\"}", result);
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames());
    }

    @Test
    void aToolThatFailsAfterItsApprovalEndsWithAToolError() {
        var session = new CapturingSession();
        var failing = ToolDefinition.builder("get_weather", "Weather for a city")
                .parameter("city", "the city", "string")
                .executor(args -> {
                    throw new IllegalStateException("weather service down");
                })
                .requiresApproval("Approve?", 60)
                .build();

        var result = ToolExecutionHelper.executeWithApproval("get_weather", failing, ARGS, session,
                strategy(ApprovalStrategy.ApprovalOutcome.APPROVED));

        assertEquals("{\"error\":\"weather service down\"}", result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
    }

    @Test
    void readingAMissingWorkspaceFileEndsWithAToolErrorAndTheModelReadsTheSameText(@TempDir Path root) {
        var session = new CapturingSession();
        var scope = Map.<Class<?>, Object>of(AgentFileSystem.class,
                new WorkspaceAgentFileSystem(root, AgentFileSystem.Limits.defaults()));

        var result = ToolExecutionHelper.executeWithApproval(FileSystemTools.READ_FILE,
                FileSystemTools.readFile(), Map.of("path", "missing.md"), session, null, null, scope);

        // The text the model reads is the one it always read; only the frame changed.
        assertEquals("Error: File not found: missing.md", result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertEquals("Error: File not found: missing.md", session.error());
    }

    @Test
    void readingAnExistingWorkspaceFileStillEndsWithItsResult(@TempDir Path root) {
        var session = new CapturingSession();
        var fs = new WorkspaceAgentFileSystem(root, AgentFileSystem.Limits.defaults());
        fs.write("notes.md", "hello");
        var scope = Map.<Class<?>, Object>of(AgentFileSystem.class, fs);

        var result = ToolExecutionHelper.executeWithApproval(FileSystemTools.READ_FILE,
                FileSystemTools.readFile(), Map.of("path", "notes.md"), session, null, null, scope);

        assertEquals("hello", result);
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames());
    }

    @Test
    void anAiToolMethodThatThrowsToolFailedExceptionEndsWithAToolErrorAndItsText() {
        var registry = new DefaultToolRegistry();
        registry.register(new FailingTools());
        var session = new CapturingSession();

        var result = ToolExecutionHelper.executeWithApproval("lookup",
                registry.getTool("lookup").orElseThrow(), Map.of("key", "k"), session, null);

        assertEquals("lookup failed: no entry for 'k'", result);
        assertEquals(List.of("ToolStart", "ToolError"), session.frames());
        assertEquals("lookup failed: no entry for 'k'", session.error());
    }

    /** An {@code @AiTool} that reports a failure the model reads verbatim. */
    public static final class FailingTools {
        @org.atmosphere.ai.annotation.AiTool(name = "lookup", description = "Look a key up")
        public String lookup(@org.atmosphere.ai.annotation.Param(value = "key", description = "the key")
                             String key) {
            throw new ToolFailedException("lookup failed: no entry for '" + key + "'");
        }
    }

    private static ToolDefinition gated() {
        return ToolDefinition.builder("get_weather", "Weather for a city")
                .parameter("city", "the city", "string")
                .executor(args -> "sunny")
                .requiresApproval("Approve?", 60)
                .build();
    }

    private static ApprovalStrategy strategy(ApprovalStrategy.ApprovalOutcome outcome) {
        return (PendingApproval approval, StreamingSession session) -> outcome;
    }

    /** Captures the tool frames a call emits. */
    private static final class CapturingSession implements StreamingSession {
        final List<AiEvent> events = new ArrayList<>();
        boolean cancelled;

        List<String> frames() {
            var out = new ArrayList<String>();
            for (var event : events) {
                switch (event) {
                    case AiEvent.ToolStart ignored -> out.add("ToolStart");
                    case AiEvent.ToolResult ignored -> out.add("ToolResult");
                    case AiEvent.ToolError ignored -> out.add("ToolError");
                    default -> {
                        // Not a tool frame.
                    }
                }
            }
            return out;
        }

        String error() {
            return assertInstanceOf(AiEvent.ToolError.class, events.get(events.size() - 1)).error();
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }

        @Override
        public boolean isTurnCancelled() {
            return cancelled;
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
