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
package org.atmosphere.interactions;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synchronous create/get/list/continue/delete coverage for the Interactions facade. */
class InteractionServiceSyncTest {

    private final InMemoryInteractionStore store = new InMemoryInteractionStore();

    private InteractionService service(Consumer<StreamingSession> script) {
        return new InteractionService(new ScriptedAgentRuntime(script), store);
    }

    private static long stepsOfType(Interaction i, String type) {
        return i.steps().stream().filter(s -> type.equals(s.type())).count();
    }

    @Test
    void createCapturesCoalescedTextToolStepsAndCompletes() {
        var svc = service(session -> {
            session.emit(new AiEvent.TextDelta("Hel"));
            session.emit(new AiEvent.TextDelta("lo"));
            session.emit(new AiEvent.ToolStart("weather", Map.of("city", "Montreal")));
            session.emit(new AiEvent.ToolResult("weather", Map.of("temp", 22)));
            session.complete();
        });

        var result = svc.create(InteractionRequest.of("hi"), new CollectingSession(), "alice");

        assertEquals(InteractionStatus.COMPLETED, result.status());
        assertEquals("Hello", result.finalText());
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_TEXT));
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_TOOL_CALL));
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_TOOL_RESULT));
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_COMPLETION));
        assertTrue(store.load(result.id()).isPresent(), "persisted when store=true");
    }

    @Test
    void storeFalsePersistsNothingButStillStreams() {
        var client = new CollectingSession();
        var svc = service(session -> {
            session.send("streamed");
            session.complete();
        });

        var result = svc.create(InteractionRequest.of("hi").withStore(false), client, "alice");

        assertEquals(InteractionStatus.COMPLETED, result.status());
        assertEquals("streamed", client.text(), "live stream is unaffected by store=false");
        assertTrue(store.load(result.id()).isEmpty(), "store=false writes no durable record");
    }

    @Test
    void ownershipIsEnforcedOnGetListDelete() {
        var svc = service(session -> session.complete());
        var owned = svc.create(InteractionRequest.of("hi"), new CollectingSession(), "alice");

        assertTrue(svc.get(owned.id(), "alice").isPresent());
        assertTrue(svc.get(owned.id(), "bob").isEmpty(), "bob cannot read alice's interaction");
        assertEquals(1, svc.list(InteractionQuery.forUser("ignored"), "alice").size());
        assertTrue(svc.list(InteractionQuery.forUser("ignored"), "bob").isEmpty());
        assertFalse(svc.delete(owned.id(), "bob"), "bob cannot delete alice's interaction");
        assertTrue(svc.delete(owned.id(), "alice"));
    }

    @Test
    void chainingRehydratesHistoryAndReusesConversationId() {
        var memory = new RecordingMemory();
        var runtime = new ScriptedAgentRuntime(session -> {
            session.send("answer");
            session.complete();
        });
        var svc = new InteractionService(runtime, store, memory);

        var first = svc.create(InteractionRequest.of("first question"), new CollectingSession(), "alice");
        assertEquals(2, memory.getHistory(first.conversationId()).size(),
                "user + assistant turn recorded");

        var second = svc.continueInteraction(first.id(),
                InteractionRequest.of("second question"), new CollectingSession(), "alice");

        assertEquals(first.conversationId(), second.conversationId(), "chain shares conversationId");
        assertEquals(first.id(), second.parentId(), "parent pointer recorded");
        var historySeenBySecond = runtime.lastContext().history();
        assertEquals(2, historySeenBySecond.size(), "second turn sees the first turn's history");
        assertEquals("first question", historySeenBySecond.get(0).content());
        assertEquals("answer", historySeenBySecond.get(1).content());
    }

    @Test
    void boundedStepsCapNonTerminalButKeepCompletion() {
        var svc = new InteractionService(new ScriptedAgentRuntime(session -> {
            for (int i = 0; i < 5; i++) {
                session.emit(new AiEvent.ToolStart("t" + i, Map.of()));
            }
            session.complete();
        }), store, null, new InteractionStepMapper(), 2, Duration.ofSeconds(5), Clock.systemUTC());

        var result = svc.create(InteractionRequest.of("hi"), new CollectingSession(), "alice");

        assertEquals(2, stepsOfType(result, InteractionStepMapper.TYPE_TOOL_CALL),
                "non-terminal steps capped at maxSteps");
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_COMPLETION),
                "terminal completion step always retained");
    }

    @Test
    void terminalTransitionIsIdempotent() {
        var svc = service(session -> {
            session.complete();
            session.emit(new AiEvent.Complete("ignored second terminal", Map.of()));
            session.complete();
        });

        var result = svc.create(InteractionRequest.of("hi"), new CollectingSession(), "alice");

        assertEquals(InteractionStatus.COMPLETED, result.status());
        assertEquals(1, stepsOfType(result, InteractionStepMapper.TYPE_COMPLETION),
                "racing terminal signals collapse to one completion step");
    }
}
