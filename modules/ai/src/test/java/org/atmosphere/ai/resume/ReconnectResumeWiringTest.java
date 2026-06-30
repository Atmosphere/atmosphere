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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the building blocks of the reconnect-triggered crash-resume consumer: the
 * run-id-carrying session adapter that streams a re-drive to the reconnected
 * client, and the run-id extraction the handler keys the resume off.
 */
class ReconnectResumeWiringTest {

    @Test
    void runIdSessionReportsRunIdAndDelegatesOutput() {
        var sink = new RecordingSession();
        var session = new RunIdStreamingSession(sink, "run-42");

        assertEquals(Optional.of("run-42"), session.runId(), "the adapter supplies the run id");

        session.send("hello");
        session.emit(new AiEvent.ToolStart("echo", java.util.Map.of()));
        session.complete();

        assertEquals("hello", sink.text.toString(), "text is forwarded to the wrapped sink");
        assertEquals(1, sink.events.size(), "events are forwarded to the wrapped sink");
        assertTrue(sink.completed, "completion is forwarded");
        assertTrue(session.isClosed(), "closed state reflects the delegate");
    }

    @Test
    void pendingRunIdReadsHeaderThenAttribute() {
        var fromAttr = mockResource("attr-run", null);
        assertEquals("attr-run", RunReattachSupport.pendingRunId(fromAttr),
                "the request attribute is preferred");

        var fromHeader = mockResource(null, "hdr-run");
        assertEquals("hdr-run", RunReattachSupport.pendingRunId(fromHeader),
                "the header is the fallback");

        var none = mockResource(null, null);
        assertNull(RunReattachSupport.pendingRunId(none), "no id present → null");
        assertNull(RunReattachSupport.pendingRunId(null), "null resource → null");
    }

    @Test
    void pendingRunIdIgnoresBlank() {
        assertNull(RunReattachSupport.pendingRunId(mockResource("   ", null)),
                "a blank id is treated as absent");
    }

    private static AtmosphereResource mockResource(String attr, String header) {
        var req = mock(AtmosphereRequest.class);
        when(req.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(attr);
        when(req.getHeader(RunReattachSupport.RUN_ID_HEADER)).thenReturn(header);
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(req);
        return resource;
    }

    private static final class RecordingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<AiEvent> events = new ArrayList<>();
        private boolean completed;

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }

        @Override
        public String sessionId() {
            return "sink";
        }

        @Override
        public void send(String text) {
            this.text.append(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void complete(String summary) {
            completed = true;
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
