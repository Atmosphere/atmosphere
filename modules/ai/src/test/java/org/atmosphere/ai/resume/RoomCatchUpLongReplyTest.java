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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A room member that reloads late in a long reply is caught up on all of it:
 * the prompt and every text delta, in order, each once. A 3b model's classroom
 * answer is 1,450 to 2,050 text deltas; the run kept its last 1,024 events, so
 * a reload in the second half of the answer came back with no prompt and an
 * answer starting mid-sentence, with nothing saying so.
 */
class RoomCatchUpLongReplyTest {

    private static final String ROOM = "/ai/room";
    private static final String SESSION = "room-session-1";
    private static final int DELTAS = 2_100;

    private RunRegistry registry;
    private AgentResumeHandle run;
    private final StringBuilder reply = new StringBuilder();

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        run = registry.register("agent", "anonymous", "prompter", new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRoomRun(null, null, ROOM), null);
        // The prompt is the room session's frame 1, the text deltas 2 to DELTAS + 1.
        var prompt = run.replayBuffer().capture(AiStreamMessage.PROMPT_TYPE, "What is a triangle?");
        run.replayBuffer().wroteFrame(prompt.sequence(), SESSION, 1);
        for (long seq = 2; seq <= DELTAS + 1; seq++) {
            var text = "w" + seq + " ";
            reply.append(text);
            var event = run.replayBuffer().capture("streaming-text", text);
            run.replayBuffer().wroteFrame(event.sequence(), SESSION, seq);
        }
    }

    @Test
    void aMemberHoldingNothingIsCaughtUpOnThePromptAndTheWholeReply() {
        var writes = new ArrayList<String>();
        var member = member(SESSION + ":0", writes);
        RoomCatchUp.prepare(member, registry, ROOM);
        var catchUp = RoomCatchUp.of(member);
        assertNotNull(catchUp);

        catchUp.write();

        assertTrue(run.replayBuffer().size() <= run.replayBuffer().capacity(), "the run stays within its capacity");
        var first = AiStreamMessage.parse(writes.get(0));
        assertEquals(AiStreamMessage.PROMPT_TYPE, first.type(), "the prompt comes first");
        assertEquals(reply.toString(), text(writes), "the whole reply, in order, each delta once");
        assertAscending(writes);
    }

    @Test
    void aMemberHoldingPartOfTheReplyIsWrittenTheRestOnly() {
        var writes = new ArrayList<String>();
        // The client holds the prompt and the text deltas up to frame 1,000.
        var member = member(SESSION + ":1000", writes);
        RoomCatchUp.prepare(member, registry, ROOM);
        RoomCatchUp.of(member).write();

        var expected = new StringBuilder();
        for (long seq = 1_001; seq <= DELTAS + 1; seq++) {
            expected.append("w").append(seq).append(' ');
        }
        assertEquals(expected.toString(), text(writes), "the text past frame 1,000, none of what the client holds");
        assertAscending(writes);
    }

    private static String text(List<String> writes) {
        var text = new StringBuilder();
        for (var json : writes) {
            var frame = AiStreamMessage.parse(json);
            if ("streaming-text".equals(frame.type())) {
                text.append(frame.data());
            }
        }
        return text.toString();
    }

    private static void assertAscending(List<String> writes) {
        var last = -1L;
        for (var json : writes) {
            var seq = AiStreamMessage.parse(json).seq();
            assertTrue(seq > last, "frame " + seq + " written after frame " + last + ": the client drops it");
            last = seq;
        }
    }

    private AtmosphereResource member(String resume, List<String> writes) {
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
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("member");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.getID()).thenReturn(ROOM);
        when(request.getHeader(ResumePoint.HEADER)).thenReturn(resume);
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
