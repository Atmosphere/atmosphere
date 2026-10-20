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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A room member back from a lost connection is written the frames of each
 * session in ascending {@code seq}, each once, whatever order the room's cache
 * and the runs' replay hold them in: the client drops a frame numbered below
 * one it holds, so a frame written late is a frame lost from the answer.
 *
 * <p>The cache's order is not the session's: a frame whose write failed as
 * the connection died is put back in the cache after the frames cached when
 * they were broadcast (the classroom drop that showed {@code 30 2 = 15} for
 * {@code 30 ÷ 2 = 15} received {@code 52, 54, 53, 55}). Nor need the replay's
 * capture order be.</p>
 */
class RoomCatchUpOrderTest {

    private static final String ROOM = "/ai/room";
    private static final String SESSION = "room-session-1";

    private RunRegistry registry;
    private AgentResumeHandle run;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        run = registry.register("agent", "anonymous", "prompter", new ExecutionHandle.Settable(() -> { }),
                RunEventReplayBuffer.forRoomRun(null, null, ROOM), null);
    }

    /** Captures a text frame the room session wrote under {@code seq}. */
    private void captured(long seq) {
        var event = run.replayBuffer().capture("streaming-text", "t" + seq);
        run.replayBuffer().wroteFrame(event.sequence(), SESSION, seq);
    }

    private static RawMessage cached(long seq) {
        return new RawMessage(new AiStreamMessage("streaming-text", "t" + seq, SESSION, seq, null, null).toJson());
    }

    private static List<Long> seqs(List<?> messages) {
        var out = new ArrayList<Long>();
        for (var message : messages) {
            var json = message instanceof RawMessage raw ? (String) raw.message() : (String) message;
            out.add(AiStreamMessage.parse(json).seq());
        }
        return out;
    }

    @Test
    void aLostWriteTheCacheKeptBehindLaterFramesIsWrittenInItsPlace() {
        for (long seq = 52; seq <= 56; seq++) {
            captured(seq);
        }
        var member = member("room-session-1:51", new ArrayList<>());
        RoomCatchUp.prepare(member, registry, ROOM);
        var catchUp = RoomCatchUp.of(member);
        assertNotNull(catchUp);

        // 52 was written into the connection before its loss was noticed (the
        // catch-up's); 54, 55, 56 were cached as broadcast; 53, whose write
        // failed as the connection died, was put back in the cache after 54.
        var merged = catchUp.mergeCached(List.of(cached(54), cached(53), cached(55), cached(56)));

        assertEquals(List.of(52L, 53L, 54L, 55L, 56L), seqs(merged),
                "each frame once, in the session's order, none written after a later one");
    }

    @Test
    void catchUpFramesCapturedOutOfTheirOrderAreWrittenInIt() {
        // Captured in the order 4, 3: the replay's order is the capture's.
        captured(4);
        captured(3);
        captured(5);
        var writes = new ArrayList<String>();
        var member = member("room-session-1:2", writes);
        RoomCatchUp.prepare(member, registry, ROOM);
        var catchUp = RoomCatchUp.of(member);
        assertNotNull(catchUp);

        var merged = catchUp.mergeCached(List.of(cached(6)));
        assertEquals(List.of(3L, 4L, 5L, 6L), seqs(merged));

        // The catch-up written on its own, with nothing cached, keeps the order too.
        var alone = member("room-session-1:2", writes);
        RoomCatchUp.prepare(alone, registry, ROOM);
        RoomCatchUp.of(alone).write();
        assertEquals(List.of(3L, 4L, 5L), seqs(writes));
    }

    @Test
    void whatTheCacheKeptIsWrittenInTheSessionsOrderWithNoCatchUp() {
        var other = new RawMessage(new AiStreamMessage("streaming-text", "x", "other-session", 7, null, null).toJson());
        var ordered = RoomCatchUp.inSessionOrder(List.of(cached(54), other, cached(53), "plain", cached(55), cached(53)));

        assertEquals(6 - 1, ordered.size(), "a frame the cache holds twice is written once");
        assertEquals(other, ordered.get(1), "another session's frame keeps its place");
        assertEquals("plain", ordered.get(3), "a message that is no frame keeps its place");
        var mine = new ArrayList<Object>();
        for (var message : ordered) {
            if (message instanceof RawMessage raw && ((String) raw.message()).contains(SESSION)) {
                mine.add(message);
            }
        }
        assertEquals(List.of(53L, 54L, 55L), seqs(mine));
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
