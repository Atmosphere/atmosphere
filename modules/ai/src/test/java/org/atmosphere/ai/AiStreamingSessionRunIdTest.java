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
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the server half of the durable run-id reconnect chain: when the
 * {@code RunRegistry} assigns a run id, {@link AiStreamingSession#setRunId}
 * surfaces it to the client as an {@code X-Atmosphere-Run-Id} metadata frame.
 * The atmosphere.js streaming client captures that key into {@code request.runId}
 * and re-sends it on reconnect, so a server with durable runs enabled resumes the
 * in-flight run from its effect journal — closing the client-reachable
 * crash-resume loop the {@code DurableSessionInterceptor} reads on the way back.
 */
class AiStreamingSessionRunIdTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(org.atmosphere.cpr.AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("session-uuid");
    }

    private AiStreamingSession session() {
        return new AiStreamingSession(delegate, new NoopRuntime(), "sys", null, List.of(), resource);
    }

    @Test
    void setRunIdSurfacesTheRunIdToTheClientAsMetadataForReconnect() {
        session().setRunId("run-xyz");

        verify(delegate).sendMetadata(RunReattachSupport.RUN_ID_HEADER, "run-xyz");
    }

    @Test
    void setRunIdEmitsNothingForANullOrBlankRunId() {
        var session = session();

        session.setRunId(null);
        session.setRunId("");
        session.setRunId("   ");

        verify(delegate, never()).sendMetadata(eq(RunReattachSupport.RUN_ID_HEADER), any());
    }

    /** Minimal runtime — setRunId never touches it. */
    private static final class NoopRuntime implements AgentRuntime {
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
        }

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }
    }
}
