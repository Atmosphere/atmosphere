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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code @Prompt} parameter-binding contract of
 * {@link PromptMethodInvoker} — the seam shared by the web handler and the
 * A2A / AG-UI bridges. With no {@code ToolSandboxBinding} on the classpath
 * (this module ships none) the invoker must bind exactly like the historic
 * {@code AiEndpointHandler.invokePrompt} loop: message, session, resource,
 * injectables (exact key then assignable), descriptive failure otherwise.
 */
public class PromptMethodInvokerTest {

    @Test
    public void bindsMessageAndSession() throws Exception {
        var target = new PromptTarget();
        var invoker = PromptMethodInvoker.forMethod(target,
                PromptTarget.class.getDeclaredMethod("twoArg",
                        String.class, StreamingSession.class));

        var session = new StubSession("s-9");
        invoker.invoke("hello", session, null, Map.of());

        assertEquals("hello", target.message);
        assertSame(session, target.session);
    }

    @Test
    public void bindsInjectablesByExactTypeAndNullResourceOnResourceFreePaths() throws Exception {
        var target = new PromptTarget();
        var invoker = PromptMethodInvoker.forMethod(target,
                PromptTarget.class.getDeclaredMethod("withInjectable",
                        String.class, StreamingSession.class, Marker.class));

        var session = new StubSession("s-10");
        var marker = new Marker();
        invoker.invoke("hi", session, null, Map.of(Marker.class, marker));

        assertEquals("hi", target.message);
        assertSame(marker, target.marker);
        assertNull(target.resource);
    }

    @Test
    public void unknownParameterTypeFailsWithDescriptiveError() throws Exception {
        var target = new PromptTarget();
        var invoker = PromptMethodInvoker.forMethod(target,
                PromptTarget.class.getDeclaredMethod("withInjectable",
                        String.class, StreamingSession.class, Marker.class));

        var thrown = assertThrows(IllegalStateException.class,
                () -> invoker.invoke("hi", new StubSession("s-11"), null, Map.of()));
        assertTrue(thrown.getMessage().contains("Unsupported parameter type"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Marker.class.getName()), thrown.getMessage());
    }

    static final class Marker {
    }

    static class PromptTarget {
        String message;
        StreamingSession session;
        Marker marker;
        Object resource;

        void twoArg(String message, StreamingSession session) {
            this.message = message;
            this.session = session;
        }

        void withInjectable(String message, StreamingSession session, Marker marker) {
            this.message = message;
            this.session = session;
            this.marker = marker;
        }
    }

    /** Minimal {@code StreamingSession} for the binding assertions. */
    static final class StubSession implements StreamingSession {
        private final String id;
        StubSession(String id) { this.id = id; }
        @Override public String sessionId() { return id; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
