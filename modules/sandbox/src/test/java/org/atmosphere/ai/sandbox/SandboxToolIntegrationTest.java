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
package org.atmosphere.ai.sandbox;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.processor.PromptMethodInvoker;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolSandboxBindings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end contract of the {@code @SandboxTool} wiring through the REAL
 * production consumers in {@code atmosphere-ai}: {@code DefaultToolRegistry}
 * (the reflective {@code @AiTool} executor) and {@link PromptMethodInvoker}
 * (the {@code @Prompt} dispatch seam used by the web handler and the A2A /
 * AG-UI bridges). Uses the ServiceLoader-registered
 * {@link RecordingSandboxProvider} — no Docker daemon and no insecure
 * in-process opt-in required.
 */
public class SandboxToolIntegrationTest {

    @BeforeEach
    public void reset() {
        RecordingSandboxProvider.reset();
        SandboxedTools.seen = null;
        PromptTarget.seen = null;
    }

    @Test
    public void bindingIsDiscoveredThroughTheToolLayerServiceLoaderSeam() throws Exception {
        var method = SandboxedTools.class.getDeclaredMethod("echo",
                String.class, Sandbox.class);
        var binding = ToolSandboxBindings.find(method);
        assertTrue(binding.isPresent());
        assertEquals(SandboxToolBinding.class, binding.get().getClass());
    }

    @Test
    public void aiToolSchemaExcludesTheSandboxParameter() {
        var registry = new DefaultToolRegistry();
        registry.register(new SandboxedTools());

        var tool = registry.getTool("sandboxed_echo").orElseThrow();
        assertEquals(1, tool.parameters().size());
        assertEquals("note", tool.parameters().get(0).name());
    }

    @Test
    public void invocationInjectsFrameworkOwnedSandboxAndClosesItAfterSuccess() {
        var registry = new DefaultToolRegistry();
        registry.register(new SandboxedTools());

        var result = registry.execute("sandboxed_echo", Map.of("note", "hi"));

        assertTrue(result.success(), () -> "unexpected failure: " + result.error());
        assertEquals("echo:hi", result.result());
        assertSame(RecordingSandboxProvider.lastSandbox, SandboxedTools.seen,
                "the method must receive the sandbox the provider created");
        assertEquals(1, RecordingSandboxProvider.lastSandbox.closeCalls(),
                "the framework must close the sandbox it created, exactly once");
    }

    @Test
    public void sandboxIsClosedWhenTheToolThrows() {
        var registry = new DefaultToolRegistry();
        registry.register(new SandboxedTools());

        var result = registry.execute("sandboxed_boom", Map.of());

        assertFalse(result.success());
        assertSame(RecordingSandboxProvider.lastSandbox, SandboxedTools.seen,
                "the tool body must have run with the injected sandbox before throwing");
        assertEquals(1, RecordingSandboxProvider.lastSandbox.closeCalls(),
                "terminal-path completeness: the sandbox must be closed on the exception path");
    }

    @Test
    public void unavailableBackendSurfacesAsAToolResultErrorNotACrash() {
        var registry = new DefaultToolRegistry();
        registry.register(new SandboxedTools());

        // registry.execute: descriptive failure, no throw.
        var result = registry.execute("sandboxed_down", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("'down'"), result.error());
        assertTrue(result.error().contains("never fall back to in-JVM execution"),
                result.error());
        assertNull(SandboxedTools.seen, "the tool body must never run without its sandbox");
        assertEquals(0, RecordingSandboxProvider.CREATE_CALLS.get(),
                "no other provider may be substituted");

        // ToolExecutionHelper (the runtime-bridge seam): the same failure is
        // encoded as an {"error": ...} tool result that flows back to the
        // model — the endpoint keeps running.
        var tool = registry.getTool("sandboxed_down").orElseThrow();
        var formatted = ToolExecutionHelper.executeAndFormat(
                tool.name(), tool.executor(), Map.of());
        assertTrue(formatted.startsWith("{\"error\":"), formatted);
        assertTrue(formatted.contains("never fall back to in-JVM execution"), formatted);
    }

    @Test
    public void promptMethodReceivesSandboxAndScopeClosesOnSuccess() throws Exception {
        var target = new PromptTarget();
        var invoker = PromptMethodInvoker.forMethod(target,
                PromptTarget.class.getDeclaredMethod("onPrompt",
                        String.class, StreamingSession.class, Sandbox.class));

        invoker.invoke("clone something", new StubSession(), null, Map.of());

        assertSame(RecordingSandboxProvider.lastSandbox, PromptTarget.seen);
        assertEquals("clone something", target.message);
        assertEquals(1, RecordingSandboxProvider.lastSandbox.closeCalls(),
                "the framework must close the @Prompt-scoped sandbox after dispatch");
    }

    @Test
    public void promptScopeClosesWhenThePromptBodyThrows() throws Exception {
        var target = new PromptTarget();
        var invoker = PromptMethodInvoker.forMethod(target,
                PromptTarget.class.getDeclaredMethod("failingPrompt",
                        String.class, StreamingSession.class, Sandbox.class));

        var thrown = assertThrows(InvocationTargetException.class,
                () -> invoker.invoke("x", new StubSession(), null, Map.of()));
        assertEquals("prompt-boom", thrown.getCause().getMessage());
        assertEquals(1, RecordingSandboxProvider.lastSandbox.closeCalls(),
                "terminal-path completeness on the @Prompt exception path");
    }

    static class SandboxedTools {
        static volatile Sandbox seen;

        @AiTool(name = "sandboxed_echo", description = "Echoes inside a sandbox")
        @SandboxTool(backend = "recording", image = "it:1")
        public String echo(@Param("note") String note, Sandbox sandbox) {
            seen = sandbox;
            return "echo:" + note;
        }

        @AiTool(name = "sandboxed_boom", description = "Always fails inside a sandbox")
        @SandboxTool(backend = "recording", image = "it:1")
        public String boom(Sandbox sandbox) {
            seen = sandbox;
            throw new IllegalStateException("boom");
        }

        @AiTool(name = "sandboxed_down", description = "Requires an unavailable backend")
        @SandboxTool(backend = "down", image = "it:1")
        public String down(Sandbox sandbox) {
            seen = sandbox;
            return "must never run";
        }
    }

    static class PromptTarget {
        static volatile Sandbox seen;
        String message;

        @SandboxTool(backend = "recording", image = "it:2", network = true)
        void onPrompt(String message, StreamingSession session, Sandbox sandbox) {
            this.message = message;
            seen = sandbox;
        }

        @SandboxTool(backend = "recording", image = "it:2")
        void failingPrompt(String message, StreamingSession session, Sandbox sandbox) {
            seen = sandbox;
            throw new IllegalStateException("prompt-boom");
        }
    }

    /** Minimal {@code StreamingSession} for the prompt-dispatch assertions. */
    static final class StubSession implements StreamingSession {
        @Override public String sessionId() { return "it-session"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
