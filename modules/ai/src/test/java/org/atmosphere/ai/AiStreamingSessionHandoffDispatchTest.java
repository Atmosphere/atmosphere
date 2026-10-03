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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A handoff runs the message as a prompt of the handing-off connection on the
 * target agent's handler. It must not go through the target's onStateChange:
 * a broadcast there is written to subscribers and never runs as a prompt.
 */
class AiStreamingSessionHandoffDispatchTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;
    private final Map<String, AtmosphereHandlerWrapper> handlers = new HashMap<>();

    @BeforeEach
    void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("support-connection");
        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(config.framework()).thenReturn(framework);
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);
    }

    private AiStreamingSession session() {
        return new AiStreamingSession(delegate, mock(AgentRuntime.class), "sys", null, List.of(), resource);
    }

    private void register(String agent, AtmosphereHandler handler) {
        var wrapper = mock(AtmosphereHandlerWrapper.class);
        when(wrapper.atmosphereHandler()).thenReturn(handler);
        handlers.put("/atmosphere/agent/" + agent, wrapper);
    }

    @Test
    void handoffRunsTheMessageAsAPromptOfTheSameConnectionOnTheTarget() throws Exception {
        var target = mock(AtmosphereHandler.class, withSettings().extraInterfaces(HandoffTarget.class));
        when(((HandoffTarget) target).acceptHandoff(resource, "my invoice")).thenReturn(true);
        register("billing", target);

        session().handoff("billing", "my invoice");

        verify((HandoffTarget) target).acceptHandoff(resource, "my invoice");
        verify(target, never()).onStateChange(any());
        verify(delegate, never()).error(any());
    }

    @Test
    void handoffWhoseConnectionIsGoneEndsWithAnError() {
        var target = mock(AtmosphereHandler.class, withSettings().extraInterfaces(HandoffTarget.class));
        when(((HandoffTarget) target).acceptHandoff(resource, "my invoice")).thenReturn(false);
        register("billing", target);

        session().handoff("billing", "my invoice");

        verify(delegate).error(any(IllegalStateException.class));
    }

    @Test
    void handoffToAHandlerThatCannotTakeAPromptEndsWithAnError() throws Exception {
        var plain = mock(AtmosphereHandler.class);
        register("billing", plain);

        session().handoff("billing", "my invoice");

        verify(plain, never()).onStateChange(any());
        verify(delegate).error(any(IllegalArgumentException.class));
    }
}
