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

import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A long-polling client is handed the run id of its reply at once, not with the
 * rest of the reply when it ends: knowing its run is what lets a client that
 * loses its connection mid-reply (a page reload) come back for it with
 * {@code X-Atmosphere-Run-Id}.
 */
class LongPollingRunIdHandOverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AtmosphereResource longPolling(String uuid) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn("/ai");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        return resource;
    }

    /** Takes what is parked for {@code uuid} through a suspended poll; empty when nothing is. */
    private static List<String> take(String uuid) {
        var poll = mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(uuid);
        when(poll.isSuspended()).thenReturn(true);
        when(poll.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var written = new ArrayList<String>();
        LongPollingReplies.deliver(poll, owner -> true, (p, frames) -> written.addAll(frames));
        return written;
    }

    private static List<String> describe(List<String> frames) {
        var described = new ArrayList<String>();
        for (var frame : frames) {
            var node = MAPPER.readTree(frame);
            var type = node.get("type").asString();
            described.add(switch (type) {
                case "metadata" -> type + ":" + node.get("key").asString() + "=" + node.get("value").asString();
                case "streaming-text" -> type + ":" + node.get("data").asString();
                default -> type;
            });
        }
        return described;
    }

    @Test
    void runIdIsHandedOverAtOnceAndTheRestOfTheReplyWhenItEnds() {
        var uuid = "lp-" + UUID.randomUUID();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));

        session.sendMetadata(RunReattachSupport.RUN_ID_HEADER, "run-1");
        assertEquals(List.of("metadata:X-Atmosphere-Run-Id=run-1"), describe(take(uuid)),
                "the run id must reach the client's next poll before the reply ends");

        session.send("Hello");
        assertTrue(take(uuid).isEmpty(), "the text still waits for the end of the reply");
        session.complete();

        assertEquals(List.of("streaming-text:Hello", "complete"), describe(take(uuid)),
                "the rest of the reply, without the run id again");
        assertTrue(take(uuid).isEmpty(), "a reply is delivered once");
    }

    @Test
    void framesBeforeTheRunIdGoWithIt() {
        var uuid = "lp-" + UUID.randomUUID();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));

        session.progress("routing");
        session.sendMetadata(RunReattachSupport.RUN_ID_HEADER, "run-2");

        assertEquals(List.of("progress", "metadata:X-Atmosphere-Run-Id=run-2"), describe(take(uuid)));
        session.complete();
        assertEquals(List.of("complete"), describe(take(uuid)));
    }

    @Test
    void otherMetadataWaitsForTheEndOfTheReply() {
        var uuid = "lp-" + UUID.randomUUID();
        var session = new DefaultStreamingSession("s-" + uuid, longPolling(uuid));

        session.sendMetadata("model", "gpt");
        session.send("Hi");
        assertTrue(take(uuid).isEmpty(), "only the run id is handed over early");
        session.complete();

        assertEquals(List.of("metadata:model=gpt", "streaming-text:Hi", "complete"), describe(take(uuid)));
    }
}
