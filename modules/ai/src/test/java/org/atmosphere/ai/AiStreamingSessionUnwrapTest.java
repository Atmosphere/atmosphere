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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AiStreamingSession#unwrap}: the session handed to a
 * {@code @Prompt} method is decorated (the endpoint handler wraps it in a
 * {@link RunEventCapturingSession} for reattach replay), so application code
 * that needs the session-scoped machinery — above all
 * {@link AiStreamingSession#approvalRegistry()}, the registry the endpoint
 * handler routes {@code /__approval/<id>/...} responses to — must be able to
 * resolve the underlying session through any decorator chain.
 */
class AiStreamingSessionUnwrapTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(org.atmosphere.cpr.AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("unwrap-test-uuid");
    }

    private AiStreamingSession session() {
        return new AiStreamingSession(delegate, new NoopRuntime(), "sys", null, List.of(), resource);
    }

    @Test
    void unwrapReturnsTheSessionItselfWhenUndecorated() {
        var session = session();
        assertSame(session, AiStreamingSession.unwrap(session).orElseThrow());
    }

    @Test
    void unwrapResolvesTheSessionBehindTheEndpointHandlerDecorator() {
        var session = session();
        // The exact decoration AiEndpointHandler applies before dispatching @Prompt.
        var decorated = new RunEventCapturingSession(session, new RunEventReplayBuffer());
        assertSame(session, AiStreamingSession.unwrap(decorated).orElseThrow());
    }

    @Test
    void unwrapIsEmptyForForeignSessionsAndNull() {
        assertFalse(AiStreamingSession.unwrap(delegate).isPresent(),
                "a leaf session with no AiStreamingSession behind it must unwrap to empty");
        assertFalse(AiStreamingSession.unwrap(null).isPresent());
    }

    @Test
    void unwrappedSessionExposesTheApprovalRegistryTheHandlerRoutesTo() {
        var session = session();
        var decorated = new RunEventCapturingSession(session, new RunEventReplayBuffer());
        var unwrapped = AiStreamingSession.unwrap(decorated).orElseThrow();
        assertSame(session.approvalRegistry(), unwrapped.approvalRegistry(),
                "unwrap must reach the registry /__approval responses resolve against");
    }

    private static final class NoopRuntime implements AgentRuntime {
        @Override public String name() {
            return "noop";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public int priority() {
            return 0;
        }

        @Override public void configure(AiConfig.LlmSettings settings) {
        }

        @Override public Set<AiCapability> capabilities() {
            return Set.of();
        }

        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
        }
    }
}
