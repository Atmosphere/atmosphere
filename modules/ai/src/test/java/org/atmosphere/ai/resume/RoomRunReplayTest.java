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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The replay of a {@code broadcastReply} run to a member of its room. Every
 * member received the run's frames under the id and numbering of the room
 * session that broadcast them: a replay written under the run id and its own
 * numbering could not be told apart from a new turn, so a member that
 * reconnected in the middle of the turn showed the frames it had twice, or
 * dropped the ones it missed. And a member the room's broadcaster cache wrote
 * the frames it missed as it rejoined is written none of them again.
 */
class RoomRunReplayTest {

    private static final String ROOM = "/ai/room";
    private static final String ROOM_SESSION = "room-session-1";

    private RunRegistry registry;
    private ExecutionHandle.Settable execution;
    private AgentResumeHandle run;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        execution = new ExecutionHandle.Settable(() -> { });
        run = registry.register("agent", "anonymous", "prompter", execution,
                RunEventReplayBuffer.forRoomRun(null, null, ROOM), null);
    }

    /** Captures {@code type} as the room session writes it under {@code seq}, as RunEventCapturingSession does. */
    private RunEvent broadcast(String type, String payload, long seq) {
        var event = run.replayBuffer().capture(type, payload);
        run.replayBuffer().wroteToRoom(event.sequence(), ROOM_SESSION, seq);
        return event;
    }

    @Test
    void aRoomRunIsReplayedUnderTheRoomSessionsIdAndNumbering() {
        var prompt = broadcast(AiStreamMessage.PROMPT_TYPE, "what is 2+2?", 2);
        var text = broadcast("streaming-text", "four", 4);

        var promptFrame = RunReattachSupport.replayFrame(prompt, run, run.runId(), 1);
        var textFrame = RunReattachSupport.replayFrame(text, run, run.runId(), 2);

        assertEquals(ROOM_SESSION, promptFrame.sessionId());
        assertEquals(2, promptFrame.seq(), "the prompt keeps the seq the room sent it with");
        assertEquals("what is 2+2?", promptFrame.text());
        assertEquals(ROOM_SESSION, textFrame.sessionId());
        assertEquals(4, textFrame.seq(), "a metadata frame between them kept its seq in the room's numbering");
    }

    @Test
    void anEventTheRoomSessionDidNotWriteIsReplayedUnderTheRunId() {
        var written = broadcast("streaming-text", "four", 3);
        var unwritten = run.replayBuffer().capture("complete", "");

        assertEquals(ROOM_SESSION, RunReattachSupport.replayFrame(written, run, run.runId(), 1).sessionId());
        var frame = RunReattachSupport.replayFrame(unwritten, run, run.runId(), 2);
        assertEquals(run.runId(), frame.sessionId());
        assertEquals(2, frame.seq());
    }

    @Test
    void theRoomFramesOfEventsTheCapacityEvictedAreForgotten() {
        var bounded = new RunEventReplayBuffer("bounded", 2);
        var first = bounded.capture("streaming-text", "a");
        bounded.wroteToRoom(first.sequence(), ROOM_SESSION, 1);
        var second = bounded.capture("streaming-text", "b");
        bounded.wroteToRoom(second.sequence(), ROOM_SESSION, 2);
        var third = bounded.capture("streaming-text", "c");
        bounded.wroteToRoom(third.sequence(), ROOM_SESSION, 3);
        // Written for an event no longer retained: ignored.
        bounded.wroteToRoom(first.sequence(), ROOM_SESSION, 9);

        assertEquals(-1, bounded.roomFrame(first), "an evicted event's frame is forgotten");
        assertEquals(2, bounded.roomFrame(second));
        assertEquals(3, bounded.roomFrame(third));
    }

    @Test
    void aSecondSessionWritingTheRunIsIgnored() {
        var event = run.replayBuffer().capture("streaming-text", "a");
        run.replayBuffer().wroteToRoom(event.sequence(), ROOM_SESSION, 5);
        run.replayBuffer().wroteToRoom(event.sequence(), "another-session", 9);

        assertEquals(ROOM_SESSION, run.replayBuffer().roomSessionId());
        assertEquals(5, run.replayBuffer().roomFrame(event));
    }

    @Test
    void aMemberTheRoomsCacheWroteTheRunToIsReplayedNothing() {
        broadcast(AiStreamMessage.PROMPT_TYPE, "q?", 2);
        broadcast("streaming-text", "par", 3);
        broadcast("streaming-text", "tial", 4);
        broadcast("complete", "", 5);
        execution.complete();

        var writes = new ArrayList<String>();
        var member = member(writes);
        RunReattachSupport.cacheDelivered(member, List.of(
                new RawMessage(new AiStreamMessage("streaming-text", "tial", ROOM_SESSION, 4, null, null).toJson()),
                new RawMessage(new AiStreamMessage("complete", null, ROOM_SESSION, 5, null, null).toJson()),
                new RawMessage("{\"type\":\"presence\",\"count\":2}")));

        assertEquals(0, RunReattachSupport.replayPendingRun(member, registry));
        assertTrue(writes.isEmpty(), "the cache wrote the frames the member missed: " + writes);
    }

    @Test
    void aMemberTheRoomsCacheWroteNothingOfTheRunIsReplayedIt() {
        broadcast("streaming-text", "par", 3);
        broadcast("complete", "", 4);
        execution.complete();

        var writes = new ArrayList<String>();
        var member = member(writes);
        RunReattachSupport.cacheDelivered(member, List.of(
                new RawMessage(new AiStreamMessage("complete", null, "another-turn", 7, null, null).toJson())));

        assertEquals(2, RunReattachSupport.replayPendingRun(member, registry));
        assertTrue(String.join("", writes).contains("\"sessionId\":\"" + ROOM_SESSION + "\""),
                "the replay keeps the room's numbering: " + writes);
    }

    /** A WebSocket member of the room reconnecting with the run id; what is written to it lands in {@code writes}. */
    private AtmosphereResource member(List<String> writes) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(AtmosphereResponse.class);
        var broadcaster = Mockito.mock(Broadcaster.class);
        var sink = new StringWriter();
        var writer = new PrintWriter(sink) {
            @Override
            public void flush() {
                super.flush();
                if (!sink.toString().isEmpty()) {
                    writes.add(sink.toString());
                    sink.getBuffer().setLength(0);
                }
            }
        };
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(RunReattachSupport.RUN_ID_ATTRIBUTE, run.runId());
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("member");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.getID()).thenReturn(ROOM);
        when(request.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.<String>getArgument(0)));
        doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1))).when(request).setAttribute(anyString(), any());
        try {
            when(resource.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(writer);
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
    }
}
