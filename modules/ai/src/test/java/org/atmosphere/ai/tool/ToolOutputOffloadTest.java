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
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deepagents-style large-tool-output disk offload in
 * {@link ToolExecutionHelper}: a result over the offload threshold is written
 * in full to the agent workspace and the model receives only a truncated
 * preview that points it at the saved file, while small results and
 * filesystem-less scopes pass through byte-for-byte unchanged.
 */
class ToolOutputOffloadTest {

    /** Matches the offload path the footer embeds ({@code tool-output/<name>-<id>.txt}). */
    private static final Pattern OFFLOAD_PATH = Pattern.compile("tool-output/\\S+\\.txt");

    @TempDir
    Path workspace;

    /** A session that carries injectables through to tools and records emitted events. */
    private static final class ScopedRecordingSession implements StreamingSession {
        private final Map<Class<?>, Object> injectables;
        final List<AiEvent> events = new ArrayList<>();
        private boolean closed;

        ScopedRecordingSession(Map<Class<?>, Object> injectables) {
            this.injectables = injectables;
        }

        @Override
        public Map<Class<?>, Object> injectables() {
            return injectables;
        }

        @Override
        public String sessionId() {
            return "conv-offload";
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
            closed = true;
        }

        @Override
        public void complete(String summary) {
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }
    }

    private static ToolDefinition toolReturning(String payload) {
        return ToolDefinition.builder("dump_logs", "Return a large blob of text")
                .parameter("q", "the query", "string")
                .executor(args -> payload)
                .build();
    }

    private AgentFileSystem fs() {
        return WorkspaceAgentFileSystem.forConversation(
                workspace, "conv-offload", AgentFileSystem.Limits.defaults());
    }

    private static AiEvent.ToolResult lastToolResult(ScopedRecordingSession session) {
        AiEvent.ToolResult last = null;
        for (var event : session.events) {
            if (event instanceof AiEvent.ToolResult result) {
                last = result;
            }
        }
        return last;
    }

    @Test
    void largeResultIsOffloadedAndPreviewPointsAtTheSavedFile() {
        var fs = fs();
        var big = "X".repeat(20_000);
        var session = new ScopedRecordingSession(Map.of(AgentFileSystem.class, fs));

        var returned = ToolExecutionHelper.executeWithApproval(
                "dump_logs", toolReturning(big), Map.of("q", "logs"), session, null, null);

        // (1) The value handed back to the model is truncated and carries both
        // the saved-file path and the read_file hint.
        assertTrue(returned.length() < big.length(),
                "offloaded result must be shorter than the full output, got " + returned.length());
        assertTrue(returned.startsWith("X".repeat(1500)),
                "preview must begin with the first 1500 chars of the full result");
        assertTrue(returned.contains("chars truncated"),
                "preview must state how much was truncated, got: " + returned);
        assertTrue(returned.contains("read_file"),
                "preview must tell the model to read_file the full output, got: " + returned);
        assertTrue(returned.contains(String.valueOf(big.length() - 1500)),
                "truncated char count must be full length minus the 1500-char preview");

        // (2) The FULL result is recoverable from the workspace at the named path.
        var matcher = OFFLOAD_PATH.matcher(returned);
        assertTrue(matcher.find(), "preview must embed the offload file path, got: " + returned);
        var path = matcher.group();
        assertTrue(path.startsWith("tool-output/dump_logs-"),
                "offload path must be under tool-output/ and carry the tool name, got: " + path);
        assertEquals(big, fs.read(path),
                "the full, untruncated output must be readable from the offload file");

        // (4) The emitted ToolResult frame carries exactly what the model saw.
        var emitted = lastToolResult(session);
        assertNotNull(emitted, "a ToolResult frame must be emitted");
        assertEquals(returned, emitted.result(),
                "the emitted ToolResult must equal the value returned to the model");
    }

    @Test
    void smallResultIsReturnedUnchangedAndWritesNoFile() {
        var fs = fs();
        var small = "the answer is 42";
        var session = new ScopedRecordingSession(Map.of(AgentFileSystem.class, fs));

        var returned = ToolExecutionHelper.executeWithApproval(
                "dump_logs", toolReturning(small), Map.of("q", "answer"), session, null, null);

        assertEquals(small, returned, "a below-threshold result must be returned verbatim");
        assertTrue(fs.glob("**/*.txt").isEmpty(),
                "no offload file may be written for a below-threshold result");
        var emitted = lastToolResult(session);
        assertNotNull(emitted);
        assertEquals(small, emitted.result());
    }

    @Test
    void largeResultWithNoFilesystemInScopeIsReturnedUnchanged() {
        var big = "Y".repeat(20_000);
        // No AgentFileSystem (nor provider) in the session's injectables.
        var session = new ScopedRecordingSession(Map.of());

        var returned = ToolExecutionHelper.executeWithApproval(
                "dump_logs", toolReturning(big), Map.of("q", "logs"), session, null, null);

        assertEquals(big, returned,
                "with no filesystem in scope a large result must pass through unchanged (never lost)");
        assertFalse(returned.contains("read_file"),
                "no offload footer may be appended when there is nothing to offload to");
        var emitted = lastToolResult(session);
        assertNotNull(emitted);
        assertEquals(big, emitted.result());
    }
}
