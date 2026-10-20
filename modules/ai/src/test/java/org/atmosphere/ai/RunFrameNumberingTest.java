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
package org.atmosphere.ai;

import org.atmosphere.ai.resume.RunEventCapturingSession;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A run's event is numbered in its session's frame numbering as it is
 * captured, before anything listening to the run sees it: a reattached
 * client is forwarded the run's next events as they are captured, and a
 * long-polling poll waiting on the run is answered with the next one, so an
 * event numbered only once its frame is written went out under the run id
 * while its replay later wrote it under the session — the same frame twice
 * under two keys, which no client can drop.
 */
class RunFrameNumberingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DefaultStreamingSession leaf;

    @AfterEach
    void tearDown() {
        if (leaf != null) {
            DefaultStreamingSession.cleanupResource("client-1");
        }
    }

    @Test
    void anEventIsNumberedInItsSessionBeforeItsListenersSeeIt() throws Exception {
        var written = new CopyOnWriteArrayList<String>();
        leaf = new DefaultStreamingSession("session-1", client(written), false);
        var buffer = new RunEventReplayBuffer("run-1", 64);
        var session = new RunEventCapturingSession(leaf, buffer);
        var seenAtCapture = new ArrayList<Long>();
        buffer.subscribe(event -> seenAtCapture.add(buffer.frameOf(event)));

        session.send("a");
        session.send("b");
        session.complete();

        var seqs = new ArrayList<Long>();
        for (var json : written) {
            var frame = MAPPER.readTree(json);
            assertEquals("session-1", frame.path("sessionId").asString());
            seqs.add(frame.path("seq").asLong());
        }
        assertEquals(seqs, seenAtCapture, "each event carried its frame's seq when its listeners saw it");
        assertEquals("session-1", buffer.frameSessionId());
    }

    @Test
    void anEventCapturedOnceTheClientIsGoneContinuesTheSessionsNumbering() throws Exception {
        var written = new CopyOnWriteArrayList<String>();
        leaf = new DefaultStreamingSession("session-1", client(written), false);
        var buffer = new RunEventReplayBuffer("run-1", 64);
        var session = new RunEventCapturingSession(leaf, buffer);
        session.send("a");
        // The client's connection is lost: its session closes, the run goes on.
        DefaultStreamingSession.cleanupResource("client-1");
        session.send("b");
        session.complete();

        var last = MAPPER.readTree(written.get(written.size() - 1)).path("seq").asLong();
        var numbers = buffer.snapshot().stream().map(buffer::frameOf).toList();
        assertEquals(List.of(last, last + 1, last + 2), numbers,
                "the events never written are numbered after the frame the client received");
        assertEquals(1, written.size());
    }

    @SuppressWarnings("unchecked")
    private static AtmosphereResource client(List<String> written) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("client-1");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), anySet())).thenAnswer(i -> {
            written.add((String) i.<RawMessage>getArgument(0).message());
            return null;
        });
        return resource;
    }
}
