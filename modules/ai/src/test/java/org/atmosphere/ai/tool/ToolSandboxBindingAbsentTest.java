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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.sandbox.Sandbox;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the zero-provider contract of the {@link ToolSandboxBinding} SPI:
 * with no binding on the classpath (this module ships none), tool
 * registration and dispatch behave exactly as before the SPI existed — no
 * scope is opened, ordinary tools run unchanged — and a method that declares
 * a sandbox-typed parameter anyway fails closed with a descriptive error
 * instead of running in-JVM with a {@code null} sandbox.
 */
public class ToolSandboxBindingAbsentTest {

    @Test
    public void findIsEmptyWhenNoBindingIsOnTheClasspath() throws Exception {
        var method = Tools.class.getDeclaredMethod("echo",
                StreamingSession.class, String.class);
        assertTrue(ToolSandboxBindings.find(method).isEmpty());
    }

    @Test
    public void ordinaryToolDispatchIsUnchangedWithoutBindings() throws Exception {
        var registry = new DefaultToolRegistry();
        registry.register(new Tools());

        var tool = registry.getTool("plain_echo").orElseThrow();
        // Schema identical to the pre-SPI behavior: only the business arg.
        assertEquals(1, tool.parameters().size());
        assertEquals("note", tool.parameters().get(0).name());

        var session = new StubSession("s-1");
        var result = tool.executor().execute(
                Map.of("note", "hi"),
                Map.<Class<?>, Object>of(StreamingSession.class, session));
        assertEquals("s-1: hi", result);
    }

    @Test
    public void sandboxTypedParamIsExcludedFromTheSchema() {
        var registry = new DefaultToolRegistry();
        registry.register(new Tools());

        var tool = registry.getTool("wants_sandbox").orElseThrow();
        // The whitelist matches by class name — the LLM must never be asked
        // to supply a Sandbox argument.
        assertEquals(1, tool.parameters().size());
        assertEquals("note", tool.parameters().get(0).name());
    }

    @Test
    public void sandboxTypedParamWithoutBindingFailsClosed() {
        var registry = new DefaultToolRegistry();
        registry.register(new Tools());

        var result = registry.execute("wants_sandbox", Map.of("note", "hi"));
        assertFalse(result.success());
        assertTrue(result.error().contains("ToolSandboxBinding"),
                "error must name the missing binding: " + result.error());
        assertTrue(result.error().contains("never fall back to in-JVM execution"),
                "error must state the no-fallback contract: " + result.error());
        assertFalse(Tools.sandboxToolRan, "the tool body must never run without a sandbox");
    }

    static class Tools {
        static volatile boolean sandboxToolRan;

        @AiTool(name = "plain_echo", description = "Echoes a note prefixed with the session id")
        public String echo(StreamingSession session, @Param("note") String note) {
            return session.sessionId() + ": " + note;
        }

        @AiTool(name = "wants_sandbox", description = "Declares a sandbox parameter")
        public String sandboxed(@Param("note") String note, Sandbox sandbox) {
            sandboxToolRan = true;
            return "ran without isolation: " + note;
        }
    }

    /** Minimal {@code StreamingSession} for the injectables assertions. */
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
