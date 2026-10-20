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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The resume point a reconnecting client sends ({@value ResumePoint#HEADER}):
 * what it last received of each session, read only when well formed and
 * bounded, and what a long-polling poll carrying it is written of a run.
 */
class ResumePointTest {

    private static final String SESSION = "6f1c2a9e-5b7d-4e0a-9f3c-1d2e3f4a5b6c";

    @Test
    void aWellFormedResumePointNamesTheLastSeqOfEachSession() {
        var point = ResumePoint.parse(SESSION + ":12,other_session-2:0").orElseThrow();

        assertEquals(12, point.lastSeq(SESSION));
        assertEquals(0, point.lastSeq("other_session-2"));
        assertEquals(-1, point.lastSeq("unknown"), "a session it does not name");
    }

    @Test
    void aMalformedOrUnboundedResumePointIsIgnoredWhole() {
        var tooMany = new StringBuilder();
        for (var i = 0; i <= ResumePoint.MAX_SESSIONS; i++) {
            tooMany.append(i == 0 ? "" : ",").append("s").append(i).append(":1");
        }
        for (var value : List.of("", SESSION, SESSION + ":", SESSION + ":-1", SESSION + ":1x",
                SESSION + ":1,", "../etc:1", "a b:1", "s:1234567890123456789", "x".repeat(65) + ":1",
                SESSION + ":1;" + SESSION + ":2", tooMany.toString(), "s:1,".repeat(1000) + "s:1")) {
            assertEquals(Optional.empty(), ResumePoint.parse(value), "ignored: " + value);
        }
        assertEquals(Optional.empty(), ResumePoint.parse(null));
    }

    @Test
    void aPollCarryingItsResumePointIsWrittenAgainTheFramesItsLostReplyHeld() {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var run = registry.register("agent", "anonymous", "client", execution,
                RunEventReplayBuffer.forRun(null, null), null);
        var buffer = run.replayBuffer();
        for (var i = 1; i <= 3; i++) {
            var event = buffer.capture("streaming-text", "chunk-" + i);
            buffer.wroteFrame(event.sequence(), SESSION, i + 1);
        }
        var end = buffer.capture("complete", "");
        buffer.wroteFrame(end.sequence(), SESSION, 5);
        execution.complete();

        // The first poll is written every frame; its reply is lost on the way.
        var lost = new ArrayList<String>();
        assertEquals(4, RunReattachSupport.replayPendingRun(poll(run, null, lost), registry));

        // The next poll says the client holds the session up to seq 2 (chunk-1).
        var writes = new ArrayList<String>();
        assertEquals(3, RunReattachSupport.replayPendingRun(poll(run, SESSION + ":2", writes), registry));
        var written = String.join("", writes);
        assertTrue(!written.contains("chunk-1") && written.contains("chunk-2") && written.contains("chunk-3")
                && written.contains("\"complete\""), "the frames past its resume point: " + written);
        assertTrue(written.contains("\"sessionId\":\"" + SESSION + "\",\"seq\":3"),
                "in the numbering of the session: " + written);

        // A poll that holds it all is written nothing more.
        var none = new ArrayList<String>();
        assertEquals(0, RunReattachSupport.replayPendingRun(poll(run, SESSION + ":5", none), registry));
        assertEquals(List.of(), none);
    }

    /** A long-polling poll of the run's client carrying its id and {@code resume}; its writes land in {@code writes}. */
    private static AtmosphereResource poll(AgentResumeHandle run, String resume, List<String> writes) {
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
        var attributes = Map.<String, Object>of(RunReattachSupport.RUN_ID_ATTRIBUTE, run.runId());
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("client");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.getID()).thenReturn("/ai/chat");
        when(request.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.<String>getArgument(0)));
        when(request.getHeader(ResumePoint.HEADER)).thenReturn(resume);
        try {
            when(resource.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(writer);
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
    }
}
