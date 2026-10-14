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

import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.agui.event.AgUiEventMapper;
import org.atmosphere.ai.AiEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgUiEventMapperCompleteTest {

    private AgUiEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AgUiEventMapper();
    }

    @Test
    void testFullConversationFlow() {
        var allEvents = new ArrayList<AgUiEvent>();

        // Phase 1: Initial text response
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("I'll ")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("check ")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("that.")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextComplete("I'll check that.")));

        // Phase 2: Tool call
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of("city", "Montreal"))));
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolResult("weather", "22C")));

        // Phase 3: Second text response
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("It's ")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("22C!")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextComplete("It's 22C!")));

        // Phase 4: Complete (produces no AG-UI events)
        allEvents.addAll(mapper.toAgUi(new AiEvent.Complete("Done", Map.of())));

        // Verify the event sequence
        // Phase 1: TextMessageStart + 3 TextMessageContent + TextMessageEnd = 5
        assertInstanceOf(AgUiEvent.TextMessageStart.class, allEvents.get(0));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, allEvents.get(1));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, allEvents.get(2));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, allEvents.get(3));
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, allEvents.get(4));

        // Phase 2: ToolCallStart + ToolCallArgs + ToolCallResult + ToolCallEnd = 4
        assertInstanceOf(AgUiEvent.ToolCallStart.class, allEvents.get(5));
        assertInstanceOf(AgUiEvent.ToolCallArgs.class, allEvents.get(6));
        assertInstanceOf(AgUiEvent.ToolCallResult.class, allEvents.get(7));
        assertInstanceOf(AgUiEvent.ToolCallEnd.class, allEvents.get(8));

        // Phase 3: TextMessageStart + 2 TextMessageContent + TextMessageEnd = 4
        assertInstanceOf(AgUiEvent.TextMessageStart.class, allEvents.get(9));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, allEvents.get(10));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, allEvents.get(11));
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, allEvents.get(12));

        // Verify message IDs
        var firstMsgStart = (AgUiEvent.TextMessageStart) allEvents.get(0);
        var secondMsgStart = (AgUiEvent.TextMessageStart) allEvents.get(9);
        assertNotEquals(firstMsgStart.messageId(), secondMsgStart.messageId(),
                "Different messages should have different IDs");
        assertEquals("msg-1", firstMsgStart.messageId());
        // TOOL_CALL_RESULT mints the tool message between the two text messages
        var toolResult = (AgUiEvent.ToolCallResult) allEvents.get(7);
        assertEquals("msg-2", toolResult.messageId());
        assertEquals("msg-3", secondMsgStart.messageId());

        // Total: 5 + 4 + 4 = 13
        assertEquals(13, allEvents.size());
    }

    @Test
    void testMultipleToolCallsGetUniqueIds() {
        var events1 = mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        mapper.toAgUi(new AiEvent.ToolResult("weather", "22C"));

        var events2 = mapper.toAgUi(new AiEvent.ToolStart("calendar", Map.of()));
        mapper.toAgUi(new AiEvent.ToolResult("calendar", "free"));

        var toolCall1 = (AgUiEvent.ToolCallStart) events1.get(0);
        var toolCall2 = (AgUiEvent.ToolCallStart) events2.get(0);

        assertNotEquals(toolCall1.toolCallId(), toolCall2.toolCallId(),
                "Different tool calls should have different IDs");
        assertEquals("tc-1", toolCall1.toolCallId());
        assertEquals("tc-2", toolCall2.toolCallId());
    }

    @Test
    void testToolErrorWithoutPriorStart() {
        // ToolError when no ToolStart was active should return empty
        var events = mapper.toAgUi(new AiEvent.ToolError("orphan", "timeout"));
        assertTrue(events.isEmpty(), "ToolError without prior ToolStart should produce empty list");
    }

    @Test
    void testToolResultWithoutPriorStart() {
        // ToolResult when no ToolStart was active should return empty
        var events = mapper.toAgUi(new AiEvent.ToolResult("orphan", "result"));
        assertTrue(events.isEmpty(), "ToolResult without prior ToolStart should produce empty list");
    }

    @Test
    void testStructuredFieldMapsToStateDelta() {
        var events = mapper.toAgUi(new AiEvent.StructuredField("name", "John", "string"));
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof AgUiEvent.StateDelta);
        var delta = (AgUiEvent.StateDelta) events.getFirst();
        assertEquals(1, delta.delta().size());
        var op = delta.delta().getFirst();
        assertEquals("add", op.get("op"));
        assertEquals("/entity/fields/name", op.get("path"));
        assertEquals(Map.of("value", "John", "type", "string"), op.get("value"));
    }

    @Test
    void testStructuredFieldNameIsEscapedAsAJsonPointerToken() {
        var events = mapper.toAgUi(new AiEvent.StructuredField("a/b~c", 1, "integer"));
        var delta = (AgUiEvent.StateDelta) events.getFirst();
        assertEquals("/entity/fields/a~1b~0c", delta.delta().getFirst().get("path"));
    }

    @Test
    void testEntityStartMapsToStateDelta() {
        var events = mapper.toAgUi(new AiEvent.EntityStart("Person", "{\"type\":\"object\"}"));
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof AgUiEvent.StateDelta);
        var delta = (AgUiEvent.StateDelta) events.getFirst();
        var op = delta.delta().getFirst();
        assertEquals("add", op.get("op"));
        assertEquals("/entity", op.get("path"));
        assertEquals("Person", ((Map<?, ?>) op.get("value")).get("entityType"));
    }

    @Test
    void testEntityCompleteMapsToStateSnapshot() {
        var events = mapper.toAgUi(new AiEvent.EntityComplete("Person", Map.of("name", "John")));
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof AgUiEvent.StateSnapshot);
        var snapshot = (AgUiEvent.StateSnapshot) events.getFirst();
        var state = (Map<?, ?>) snapshot.snapshot();
        assertEquals("Person", state.get("entityType"));
        assertEquals(Map.of("name", "John"), state.get("entity"));
    }

    @Test
    void testRoutingDecisionReturnsEmpty() {
        var events = mapper.toAgUi(new AiEvent.RoutingDecision("gpt-4", "claude", "cost optimization"));
        assertTrue(events.isEmpty(), "RoutingDecision should have no AG-UI mapping");
    }

    @Test
    void testProgressWithNullPercentage() {
        var events = mapper.toAgUi(new AiEvent.Progress("Loading...", null));

        assertEquals(1, events.size());
        var snapshot = assertInstanceOf(AgUiEvent.ActivitySnapshot.class, events.get(0));
        assertNotNull(snapshot.messageId());
        assertEquals("progress", snapshot.activityType());
        assertEquals("Loading...", snapshot.content().get("message"));
        // With null percentage, mapper uses -1 as default
        assertEquals(-1.0, snapshot.content().get("percentage"));
    }

    @Test
    void testProgressWithPercentage() {
        mapper.toAgUi(new AiEvent.Progress("Started", 0.1));
        var events = mapper.toAgUi(new AiEvent.Progress("Almost done", 0.95));

        assertEquals(1, events.size());
        var delta = assertInstanceOf(AgUiEvent.ActivityDelta.class, events.get(0));
        assertEquals(Map.of("op", "replace", "path", "/percentage", "value", 0.95),
                delta.patch().get(1));
    }

    @Test
    void testResetStartsANewProgressActivity() {
        var first = (AgUiEvent.ActivitySnapshot) mapper.toAgUi(new AiEvent.Progress("one", null)).getFirst();
        mapper.reset();
        var next = mapper.toAgUi(new AiEvent.Progress("two", null)).getFirst();
        var snapshot = assertInstanceOf(AgUiEvent.ActivitySnapshot.class, next,
                "a new run has no activity to patch, so it must snapshot again");
        assertNotEquals(first.messageId(), snapshot.messageId());
    }

    @Test
    void testTextDeltaAfterResetStartsNewMessage() {
        // First message
        mapper.toAgUi(new AiEvent.TextDelta("Hello"));
        mapper.toAgUi(new AiEvent.TextComplete("Hello"));

        // Reset mapper state
        mapper.reset();

        // Second message after reset should start fresh
        var events = mapper.toAgUi(new AiEvent.TextDelta("Hi again"));
        assertEquals(2, events.size());
        assertInstanceOf(AgUiEvent.TextMessageStart.class, events.get(0));
        assertInstanceOf(AgUiEvent.TextMessageContent.class, events.get(1));

        // After reset, message counter is NOT reset (counters use AtomicInteger),
        // but currentMessageId is cleared so a new message starts
        var start = (AgUiEvent.TextMessageStart) events.get(0);
        assertNotNull(start.messageId());
    }

    @Test
    void testResetClearsToolCallState() {
        mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        mapper.reset();

        // After reset, ToolResult without prior ToolStart should return empty
        var events = mapper.toAgUi(new AiEvent.ToolResult("weather", "result"));
        assertTrue(events.isEmpty(),
                "ToolResult after reset should return empty since currentToolCallId was cleared");
    }

    @Test
    void testConcurrentMapperAccess() throws Exception {
        int threadCount = 10;
        int iterationsPerThread = 50;
        var latch = new CountDownLatch(threadCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int t = 0; t < threadCount; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        mapper.toAgUi(new AiEvent.TextDelta("chunk-" + i));
                        mapper.toAgUi(new AiEvent.Progress("progress", 0.5));
                        mapper.toAgUi(new AiEvent.AgentStep("s1", "step", Map.of()));
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        assertTrue(errors.isEmpty(), "No exceptions should be thrown: " + errors);
    }

    @Test
    void testAgentStepsAreIdentifiedByName() {
        var events1 = mapper.toAgUi(new AiEvent.AgentStep("s1", "Step One", Map.of()));
        var events2 = mapper.toAgUi(new AiEvent.AgentStep("s2", "Step Two", Map.of()));

        var step1Start = (AgUiEvent.StepStarted) events1.get(0);
        var step2Start = (AgUiEvent.StepStarted) events2.get(0);

        assertEquals("s1", step1Start.stepName());
        assertEquals("s2", step2Start.stepName());
    }

    @Test
    void testAgentStepCarriesStepName() {
        var events = mapper.toAgUi(new AiEvent.AgentStep("analyze", "Analyzing input data", Map.of()));

        assertEquals(2, events.size());
        var started = (AgUiEvent.StepStarted) events.get(0);
        var finished = (AgUiEvent.StepFinished) events.get(1);

        // The mapper uses stepName, not description; STEP_FINISHED names the
        // step it closes.
        assertEquals("analyze", started.stepName());
        assertEquals("analyze", finished.stepName());
    }

    @Test
    void testErrorEventCarriesTheStringCode() {
        var events = mapper.toAgUi(new AiEvent.Error("Rate limited", "rate_limit", true));

        assertEquals(1, events.size());
        var error = (AgUiEvent.RunError) events.get(0);
        assertEquals("Rate limited", error.message());
        assertEquals("rate_limit", error.code());
    }

    @Test
    void testErrorEventWithNullCode() {
        var events = mapper.toAgUi(new AiEvent.Error("Unknown error", null, false));

        assertEquals(1, events.size());
        var error = (AgUiEvent.RunError) events.get(0);
        assertEquals("Unknown error", error.message());
        assertNull(error.code(), "A null code stays absent from the wire");
    }

    @Test
    void testErrorEventHasNullRunId() {
        var events = mapper.toAgUi(new AiEvent.Error("fail", "500", false));

        var error = (AgUiEvent.RunError) events.get(0);
        assertEquals(null, error.runId(), "Mapper-generated RunError should have null runId");
    }

    @Test
    void testToolCallStartHasParentMessageId() {
        // Start a text message first to establish currentMessageId
        mapper.toAgUi(new AiEvent.TextDelta("Let me check"));

        // Now start a tool call - it should reference the current message
        var events = mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        var toolStart = (AgUiEvent.ToolCallStart) events.get(0);

        assertEquals("msg-1", toolStart.parentMessageId(),
                "ToolCallStart should reference the current message as parent");
    }

    @Test
    void testToolCallStartWithoutParentMessage() {
        // Start a tool call without any prior text message
        var events = mapper.toAgUi(new AiEvent.ToolStart("weather", Map.of()));
        var toolStart = (AgUiEvent.ToolCallStart) events.get(0);

        assertEquals(null, toolStart.parentMessageId(),
                "ToolCallStart without prior message should have null parentMessageId");
    }

    @Test
    void testToolCallArgsContainsSerializedArguments() {
        var events = mapper.toAgUi(new AiEvent.ToolStart("search",
                Map.of("query", "weather montreal", "limit", 5)));

        assertEquals(2, events.size());
        var args = (AgUiEvent.ToolCallArgs) events.get(1);
        assertTrue(args.delta().contains("query"), "Args should contain parameter names");
        assertTrue(args.delta().contains("weather montreal"), "Args should contain parameter values");
    }

    @Test
    void testToolResultWithNonStringResult() {
        mapper.toAgUi(new AiEvent.ToolStart("calc", Map.of()));

        var events = mapper.toAgUi(new AiEvent.ToolResult("calc", Map.of("value", 42)));

        assertFalse(events.isEmpty());
        var result = (AgUiEvent.ToolCallResult) events.get(0);
        // Non-string result should be serialized to JSON
        assertTrue(result.content().contains("42"), "Non-string result should be serialized");
    }

    @Test
    void testCompleteEventReturnsEmptyList() {
        var events = mapper.toAgUi(new AiEvent.Complete("summary", Map.of("tokens", 100)));
        assertTrue(events.isEmpty(), "Complete should return empty list (handled by session)");
    }

    @Test
    void testTextMessageContentContainsCorrectDelta() {
        mapper.toAgUi(new AiEvent.TextDelta("First"));
        var events = mapper.toAgUi(new AiEvent.TextDelta("Second"));

        assertEquals(1, events.size());
        var content = (AgUiEvent.TextMessageContent) events.get(0);
        assertEquals("Second", content.delta(), "Content delta should match the emitted text");
    }

    @Test
    void testTextCompleteWithNoActiveMessage() {
        // TextComplete without any prior TextDelta
        var events = mapper.toAgUi(new AiEvent.TextComplete("orphan text"));
        assertTrue(events.isEmpty(),
                "TextComplete without active message should return empty list");
    }

    @Test
    void testAlternatingTextAndTools() {
        var allEvents = new ArrayList<AgUiEvent>();

        // Text -> Tool -> Text -> Tool -> Text
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("Checking...")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextComplete("Checking...")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolStart("search", Map.of())));
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolResult("search", "found")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("Found it!")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextComplete("Found it!")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolStart("verify", Map.of())));
        allEvents.addAll(mapper.toAgUi(new AiEvent.ToolResult("verify", "confirmed")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextDelta("Confirmed!")));
        allEvents.addAll(mapper.toAgUi(new AiEvent.TextComplete("Confirmed!")));

        // Count event types
        long textStarts = allEvents.stream()
                .filter(e -> e instanceof AgUiEvent.TextMessageStart).count();
        long textEnds = allEvents.stream()
                .filter(e -> e instanceof AgUiEvent.TextMessageEnd).count();
        long toolStarts = allEvents.stream()
                .filter(e -> e instanceof AgUiEvent.ToolCallStart).count();
        long toolEnds = allEvents.stream()
                .filter(e -> e instanceof AgUiEvent.ToolCallEnd).count();

        assertEquals(3, textStarts, "Should have 3 text messages");
        assertEquals(3, textEnds, "Should have 3 text message ends");
        assertEquals(2, toolStarts, "Should have 2 tool calls");
        assertEquals(2, toolEnds, "Should have 2 tool call ends");
    }
}
