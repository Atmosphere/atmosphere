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
package org.atmosphere.agui;

import org.atmosphere.agui.runtime.AgUiStreamingSession;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgUiStreamingSessionTest {

    private StreamingSession delegate;
    private AtmosphereResponse response;
    private StringWriter output;
    private AgUiStreamingSession session;
    private RunContext runContext;

    @BeforeEach
    void setUp() throws Exception {
        delegate = mock(StreamingSession.class);
        when(delegate.sessionId()).thenReturn("test-session");
        response = mock(AtmosphereResponse.class);
        output = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        runContext = new RunContext("thread-1", "run-1", List.of(), Map.of(), Map.of(), List.of());
        session = new AgUiStreamingSession(delegate, response, runContext);
    }

    @Test
    void testTextDeltaEmitsTextMessageStartAndContent() {
        session.emit(new AiEvent.TextDelta("Hello"));

        var sse = output.toString();
        assertTrue(sse.contains("event: TEXT_MESSAGE_START"), "Should contain TextMessageStart event");
        assertTrue(sse.contains("event: TEXT_MESSAGE_CONTENT"), "Should contain TextMessageContent event");
        assertTrue(sse.contains("\"delta\":\"Hello\""), "Should contain the delta text");
        assertTrue(sse.contains("\"role\":\"assistant\""), "Should contain assistant role");
    }

    @Test
    void testMultipleTextDeltasShareSameMessageId() {
        session.emit(new AiEvent.TextDelta("Hello"));
        session.emit(new AiEvent.TextDelta(" World"));
        session.emit(new AiEvent.TextDelta("!"));

        var sse = output.toString();
        // Only 1 TextMessageStart
        var startCount = countOccurrences(sse, "event: TEXT_MESSAGE_START");
        assertEquals(1, startCount, "Should emit only one TextMessageStart");

        // 3 TextMessageContent events
        var contentCount = countOccurrences(sse, "event: TEXT_MESSAGE_CONTENT");
        assertEquals(3, contentCount, "Should emit 3 TextMessageContent events");

        // All content events share the same messageId (msg-1)
        assertTrue(sse.contains("\"messageId\":\"msg-1\""), "Should use messageId msg-1");
    }

    @Test
    void testTextCompleteEmitsTextMessageEnd() {
        session.emit(new AiEvent.TextDelta("Hello"));
        session.emit(new AiEvent.TextComplete("Hello World"));

        var sse = output.toString();
        assertTrue(sse.contains("event: TEXT_MESSAGE_END"), "Should contain TextMessageEnd event");
        assertTrue(sse.contains("\"messageId\":\"msg-1\""), "TextMessageEnd should reference same messageId");
    }

    @Test
    void testToolStartEmitsToolCallEvents() {
        session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));

        var sse = output.toString();
        assertTrue(sse.contains("event: TOOL_CALL_START"), "Should contain ToolCallStart event");
        assertTrue(sse.contains("event: TOOL_CALL_ARGS"), "Should contain ToolCallArgs event");
        assertTrue(sse.contains("\"name\":\"weather\""), "Should contain tool name");
        assertTrue(sse.contains("\"toolCallId\":\"tc-1\""), "Should contain toolCallId");
    }

    @Test
    void testToolResultEmitsToolCallResultAndEnd() {
        session.emit(new AiEvent.ToolStart("weather", Map.of()));
        session.emit(new AiEvent.ToolResult("weather", "22C"));

        var sse = output.toString();
        assertTrue(sse.contains("event: TOOL_CALL_RESULT"), "Should contain ToolCallResult event");
        assertTrue(sse.contains("event: TOOL_CALL_END"), "Should contain ToolCallEnd event");
        assertTrue(sse.contains("\"result\":\"22C\""), "Should contain the tool result");
    }

    @Test
    void testAgentStepEmitsStepEvents() {
        session.emit(new AiEvent.AgentStep("s1", "Analyzing data", Map.of()));

        var sse = output.toString();
        assertTrue(sse.contains("event: STEP_STARTED"), "Should contain StepStarted event");
        assertTrue(sse.contains("event: STEP_FINISHED"), "Should contain StepFinished event");
        assertTrue(sse.contains("\"name\":\"s1\""), "Should contain step name (stepName, not description)");
    }

    @Test
    void testProgressEmitsActivityDelta() {
        session.emit(new AiEvent.Progress("Loading...", 0.5));

        var sse = output.toString();
        assertTrue(sse.contains("event: ACTIVITY_DELTA"), "Should contain ActivityDelta event");
        assertTrue(sse.contains("Loading..."), "Should contain progress message");
    }

    @Test
    void testErrorEmitsRunError() {
        session.emit(new AiEvent.Error("Something failed", "500", false));

        var sse = output.toString();
        assertTrue(sse.contains("event: RUN_ERROR"), "Should contain RunError event");
        assertTrue(sse.contains("\"message\":\"Something failed\""), "Should contain error message");
    }

    @Test
    void testCompleteEmitsRunFinished() {
        session.complete();

        var sse = output.toString();
        assertTrue(sse.contains("event: RUN_FINISHED"), "Should contain RunFinished event");
        assertTrue(sse.contains("\"runId\":\"run-1\""), "Should contain the runId");
        assertTrue(sse.contains("\"threadId\":\"thread-1\""), "Should contain the threadId");
        verify(delegate).complete(null);
    }

    @Test
    void testCompleteWithSummaryEmitsRunFinished() {
        session.complete("All done");

        var sse = output.toString();
        assertTrue(sse.contains("event: RUN_FINISHED"), "Should contain RunFinished event");
        verify(delegate).complete("All done");
    }

    @Test
    void testErrorCallEmitsRunErrorAndDelegates() {
        var exception = new RuntimeException("Network timeout");
        session.error(exception);

        var sse = output.toString();
        assertTrue(sse.contains("event: RUN_ERROR"), "Should contain RunError event");
        assertTrue(sse.contains("\"message\":\"Network timeout\""), "Should contain error message");
        assertTrue(sse.contains("\"runId\":\"run-1\""), "Should contain the runId");
        verify(delegate).error(exception);
    }

    @Test
    void testDelegateReceivesAllEvents() {
        var textDelta = new AiEvent.TextDelta("Hello");
        var toolStart = new AiEvent.ToolStart("calc", Map.of("x", 1));
        var progress = new AiEvent.Progress("Working...", null);

        session.emit(textDelta);
        session.emit(toolStart);
        session.emit(progress);

        verify(delegate).emit(textDelta);
        verify(delegate).emit(toolStart);
        verify(delegate).emit(progress);
    }

    @Test
    void testSseFrameFormat() {
        session.emit(new AiEvent.TextDelta("Hi"));

        var sse = output.toString();
        // Each SSE frame follows: event: TYPE\ndata: {json}\n\n
        var frames = sse.split("\n\n");
        for (var frame : frames) {
            if (frame.isBlank()) {
                continue;
            }
            var lines = frame.split("\n");
            assertTrue(lines[0].startsWith("event: "), "Frame should start with 'event: '");
            assertTrue(lines[1].startsWith("data: "), "Frame should have 'data: ' on second line");
            assertTrue(lines[1].contains("{"), "Data should be JSON");
        }
    }

    @Test
    void testSessionIdDelegates() {
        assertEquals("test-session", session.sessionId());
    }

    @Test
    void testDoubleCompleteIsIgnored() {
        session.complete();
        session.complete();

        var sse = output.toString();
        var finishedCount = countOccurrences(sse, "event: RUN_FINISHED");
        assertEquals(1, finishedCount, "Should emit only one RunFinished event");
        // delegate.complete() should only be called once
        verify(delegate).complete(null);
    }

    @Test
    void testSendCallsEmitTextDelta() {
        session.send("text content");

        var sse = output.toString();
        assertTrue(sse.contains("event: TEXT_MESSAGE_START"), "send() should trigger TextMessageStart");
        assertTrue(sse.contains("event: TEXT_MESSAGE_CONTENT"), "send() should trigger TextMessageContent");
        assertTrue(sse.contains("\"delta\":\"text content\""), "Should contain the sent text");
        // Delegate should receive the emit call
        verify(delegate).emit(new AiEvent.TextDelta("text content"));
    }

    @Test
    void testProgressCallEmitsProgressEvent() {
        session.progress("Thinking...");

        var sse = output.toString();
        assertTrue(sse.contains("event: ACTIVITY_DELTA"), "progress() should trigger ActivityDelta");
        verify(delegate).emit(new AiEvent.Progress("Thinking...", null));
    }

    @Test
    void testIsClosedReturnsFalseInitially() {
        assertFalse(session.isClosed());
    }

    @Test
    void testIsClosedReturnsTrueAfterComplete() {
        session.complete();
        assertTrue(session.isClosed());
    }

    @Test
    void testIsClosedReturnsTrueAfterError() {
        session.error(new RuntimeException("fail"));
        assertTrue(session.isClosed());
    }

    @Test
    void testErrorAfterCompleteIsIgnored() {
        session.complete();
        session.error(new RuntimeException("late error"));

        // Should not have called delegate.error()
        verify(delegate, never()).error(org.mockito.ArgumentMatchers.any());
        // Only RunFinished, no RunError
        var sse = output.toString();
        assertFalse(sse.contains("event: RUN_ERROR"), "Error after complete should be ignored");
    }

    @Test
    void testCompleteAfterErrorIsIgnored() {
        session.error(new RuntimeException("fail"));
        // Reset output to isolate second call
        output.getBuffer().setLength(0);
        session.complete();

        var sse = output.toString();
        assertFalse(sse.contains("event: RUN_FINISHED"), "Complete after error should be ignored");
    }

    @Test
    void testSendMetadataDelegatesToDelegate() {
        session.sendMetadata("model", "gpt-4");
        verify(delegate).sendMetadata("model", "gpt-4");
    }

    @Test
    void testStreamDelegatesToDelegate() {
        session.stream("user message");
        verify(delegate).stream("user message");
    }

    @Test
    void testRunContextAccessor() {
        assertEquals(runContext, session.runContext());
        assertEquals("thread-1", session.runContext().threadId());
        assertEquals("run-1", session.runContext().runId());
    }

    @Test
    void testToolErrorEmitsToolCallEnd() {
        session.emit(new AiEvent.ToolStart("calc", Map.of()));
        session.emit(new AiEvent.ToolError("calc", "division by zero"));

        var sse = output.toString();
        assertTrue(sse.contains("event: TOOL_CALL_END"), "ToolError should emit ToolCallEnd");
    }

    @Test
    void testTextCompleteWithoutPriorDelta() {
        // TextComplete without any prior TextDelta should produce empty list
        session.emit(new AiEvent.TextComplete("orphan"));

        var sse = output.toString();
        // No TextMessageEnd should appear since no message was started
        assertFalse(sse.contains("event: TEXT_MESSAGE_END"),
                "TextComplete without prior TextDelta should not emit TextMessageEnd");
    }

    @Test
    void testFullConversationFlowThroughSession() {
        // Simulate a realistic conversation flow
        session.emit(new AiEvent.TextDelta("Let me "));
        session.emit(new AiEvent.TextDelta("check the weather."));
        session.emit(new AiEvent.TextComplete("Let me check the weather."));
        session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));
        session.emit(new AiEvent.ToolResult("weather", "22C sunny"));
        session.emit(new AiEvent.TextDelta("It's 22C and sunny!"));
        session.emit(new AiEvent.TextComplete("It's 22C and sunny!"));
        session.complete("Done");

        var sse = output.toString();
        // Verify the sequence
        assertTrue(sse.contains("event: TEXT_MESSAGE_START"));
        assertTrue(sse.contains("event: TEXT_MESSAGE_CONTENT"));
        assertTrue(sse.contains("event: TEXT_MESSAGE_END"));
        assertTrue(sse.contains("event: TOOL_CALL_START"));
        assertTrue(sse.contains("event: TOOL_CALL_ARGS"));
        assertTrue(sse.contains("event: TOOL_CALL_RESULT"));
        assertTrue(sse.contains("event: TOOL_CALL_END"));
        assertTrue(sse.contains("event: RUN_FINISHED"));

        // Should have 2 TextMessageStart events (one for each text message)
        var startCount = countOccurrences(sse, "event: TEXT_MESSAGE_START");
        assertEquals(2, startCount, "Should have 2 TextMessageStart events");

        // Verify delegate received complete
        verify(delegate).complete("Done");
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
