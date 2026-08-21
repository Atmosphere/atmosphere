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
package org.atmosphere.agui.runtime;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the AG-UI text-message completion contract of
 * {@link AgUiHandler.ResourceAgUiStreamingSession}:
 *
 * <ul>
 *   <li>Pipeline-style dispatches ({@code send()} deltas then {@code complete()},
 *       no explicit {@link AiEvent.TextComplete}) must close the in-flight text
 *       message — exactly one {@code TEXT_MESSAGE_END} before
 *       {@code RUN_FINISHED}.</li>
 *   <li>Legacy emitters that send an explicit {@code TextComplete} must NOT get
 *       a duplicate {@code TEXT_MESSAGE_END} from {@code complete()} —
 *       close-once semantics.</li>
 *   <li>{@code error()} emits a terminal {@code RUN_ERROR} without fabricating
 *       a {@code TEXT_MESSAGE_END} for a message that failed mid-stream.</li>
 *   <li>A dangling tool call ({@code ToolStart} with no {@code ToolResult})
 *       is closed with {@code TOOL_CALL_END} by {@code complete()}.</li>
 *   <li>No frames are written after the run is terminal — {@code send()}
 *       after {@code complete()} is dropped.</li>
 * </ul>
 */
class ResourceAgUiSessionCompletionTest {

    private StringWriter output;
    private AgUiHandler.ResourceAgUiStreamingSession session;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        var writer = new AgUiHandler.SseWriter(new PrintWriter(output));
        var runContext = new RunContext("t1", "r1", List.of(), null, null, null);
        session = new AgUiHandler.ResourceAgUiStreamingSession(
                new StubSession(), writer, runContext);
    }

    @Test
    void pipelineStyleCompleteClosesOpenTextMessage() {
        // Pipeline-routed dispatch: N send() deltas, then complete() — the
        // runtime never emits an explicit TextComplete on this path.
        session.send("Hello ");
        session.send("world");
        session.complete("Hello world");

        var frames = frames();
        assertEquals(List.of(
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(frames),
                "send()+complete() must yield START, N CONTENT, one END, then RUN_FINISHED");

        // The close frame targets the same message the start frame opened.
        assertTrue(frame(frames, "TEXT_MESSAGE_START").contains("\"messageId\":\"msg-1\""));
        assertTrue(frame(frames, "TEXT_MESSAGE_END").contains("\"messageId\":\"msg-1\""));
    }

    @Test
    void explicitTextCompleteDoesNotDuplicateEnd() {
        // Legacy emitter: an explicit terminal TextComplete already closed the
        // message; complete() must not write a second TEXT_MESSAGE_END.
        session.send("Hi ");
        session.emit(new AiEvent.TextComplete("Hi"));
        session.complete("Hi");

        var frames = frames();
        assertEquals(List.of(
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(frames),
                "explicit TextComplete + complete() must close the message exactly once");
    }

    @Test
    void completeWithoutTextEmitsNoEnd() {
        session.complete();

        assertEquals(List.of("RUN_FINISHED"), types(frames()),
                "complete() with no open message must emit only RUN_FINISHED");
    }

    @Test
    void errorEmitsRunErrorWithoutFabricatedEnd() {
        // RUN_ERROR is a terminal hard-abort the AG-UI protocol permits while
        // a text message is open; an END would falsely signal normal completion.
        session.send("partial ");
        session.error(new RuntimeException("boom"));

        var frames = frames();
        assertEquals(List.of(
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "RUN_ERROR"),
                types(frames),
                "error() must terminate with RUN_ERROR and no fabricated TEXT_MESSAGE_END");
        assertTrue(frame(frames, "RUN_ERROR").contains("boom"));
    }

    @Test
    void completeIsIdempotent() {
        session.send("once ");
        session.complete("once");
        session.complete("again");

        var types = types(frames());
        assertEquals(1, types.stream().filter("RUN_FINISHED"::equals).count(),
                "second complete() must be a no-op (close-once CAS)");
        assertEquals(1, types.stream().filter("TEXT_MESSAGE_END"::equals).count(),
                "second complete() must not re-close the message");
    }

    @Test
    void completeClosesDanglingToolCall() {
        // A ToolStart with no ToolResult/ToolError before complete() must not
        // leave TOOL_CALL_START unclosed — complete() closes the innermost
        // scope (tool call) first, then the text message, then the run.
        session.send("Calling tool ");
        session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Paris")));
        session.complete();

        var frames = frames();
        assertEquals(List.of(
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(frames),
                "complete() must close a dangling tool call before the text close and RUN_FINISHED");
        assertTrue(frame(frames, "TOOL_CALL_END").contains("\"toolCallId\":\"tc-1\""),
                "the close frame must target the tool call the start frame opened");
    }

    @Test
    void sendAfterCompleteWritesNothing() {
        session.send("on time");
        session.complete();
        var terminal = output.toString();

        session.send("too late");

        assertEquals(terminal, output.toString(),
                "frames after RUN_FINISHED are protocol violations — send() after complete() must write nothing");
    }

    /**
     * Regression (registre#13): {@code sendContent} was an empty body on the
     * live AG-UI session, so every code-execution artifact was dropped with
     * no event and no breadcrumb. Text content must reach the wire as
     * TEXT_MESSAGE frames; binary content must leave a
     * {@code content.binary.dropped} breadcrumb via CUSTOM.
     */
    @Test
    void sendContentReachesTheWireInsteadOfVanishing() {
        session.sendContent(new Content.Text("artifact output"));
        session.sendContent(new Content.Image(new byte[] {1, 2, 3}, "png"));
        session.complete();

        var frames = frames();
        assertTrue(types(frames).contains("TEXT_MESSAGE_CONTENT"),
                "text content must stream as message content: " + types(frames));
        assertTrue(frame(frames, "TEXT_MESSAGE_CONTENT").contains("artifact output"));
        assertTrue(frame(frames, "CUSTOM").contains("content.binary.dropped"),
                "binary content must leave a breadcrumb, not vanish: " + frames);
    }

    /**
     * Regression (registre#13): {@code sendMetadata} was an empty body, so a
     * client never learned which model answered, whether the cache hit, or
     * that the budget degraded. Metadata rides AG-UI's CUSTOM event.
     */
    @Test
    void sendMetadataEmitsACustomEvent() {
        session.sendMetadata("ai.model.used", "claude-sonnet-5");
        session.complete();

        var custom = frame(frames(), "CUSTOM");
        assertTrue(custom.contains("ai.model.used") && custom.contains("claude-sonnet-5"),
                "response metadata must reach the client: " + custom);
    }

    @Test
    void sendMetadataAfterTerminalFrameWritesNothing() {
        session.complete();
        var terminal = output.toString();

        session.sendMetadata("late", "value");

        assertEquals(terminal, output.toString());
    }

    /** Parsed SSE frame: {@code type} + raw JSON payload. */
    private record Frame(String type, String data) {
    }

    private List<Frame> frames() {
        var frames = new ArrayList<Frame>();
        String currentType = null;
        for (var line : output.toString().split("\n")) {
            if (line.startsWith("event: ")) {
                currentType = line.substring(7).trim();
            } else if (line.startsWith("data: ") && currentType != null) {
                frames.add(new Frame(currentType, line.substring(6)));
                currentType = null;
            }
        }
        return frames;
    }

    private static List<String> types(List<Frame> frames) {
        return frames.stream().map(Frame::type).toList();
    }

    private static String frame(List<Frame> frames, String type) {
        return frames.stream()
                .filter(f -> f.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no frame of type " + type))
                .data();
    }

    /** Minimal delegate — the session under test only reads sessionId() from it. */
    private static final class StubSession implements StreamingSession {
        @Override public String sessionId() { return "stub"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
        @Override public void emit(AiEvent event) { }
        @Override public void sendContent(Content content) { }
    }
}
