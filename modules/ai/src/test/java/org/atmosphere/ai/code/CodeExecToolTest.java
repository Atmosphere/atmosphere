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
package org.atmosphere.ai.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.code.SandboxCommand.Language;
import org.atmosphere.ai.llm.ToolLoopPolicies;
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code code_exec} tool executor, the lazy
 * {@link SessionSandbox}, and the {@link CodeExecSupport} integration seam.
 */
class CodeExecToolTest {

    private static final SandboxResult OK =
            new SandboxResult(0, "hello\n", "", false, false, Duration.ofMillis(3), java.util.List.of());

    // --- code_exec tool executor ---------------------------------------------

    @Test
    void toolDefinitionExposesNameAndParameters() {
        var def = CodeExecTool.definition();
        assertEquals(CodeExecTool.TOOL_NAME, def.name());
        var paramNames = def.parameters().stream().map(p -> p.name()).toList();
        assertTrue(paramNames.contains("language"));
        assertTrue(paramNames.contains("code"));
    }

    @Test
    void executorRunsCodeInSessionSandboxAndReturnsStructuredResult() throws Exception {
        var sandbox = new FakeSandbox(OK);
        ToolExecutor exec = CodeExecTool.definition().executor();
        Object result = exec.execute(
                Map.of("language", "javascript", "code", "console.log('hello')"),
                Map.of(CodeSandbox.class, sandbox));

        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) result;
        assertEquals(0, view.get("exitCode"));
        assertEquals("hello\n", view.get("stdout"));
        assertEquals(Language.JAVASCRIPT, sandbox.last.language());
        assertEquals("console.log('hello')", sandbox.last.code());
    }

    @Test
    void executorStreamsAgentStepAndArtifactsToSession() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        var withShot = new SandboxResult(0, "done", "", false, false,
                java.time.Duration.ofMillis(4),
                java.util.List.of(new SandboxArtifact("shot.png", "image/png", png)));
        var sandbox = new FakeSandbox(withShot);
        var session = new CapturingSession();

        CodeExecTool.definition().executor().execute(
                Map.of("language", "javascript", "code", "await page.screenshot()"),
                Map.of(CodeSandbox.class, sandbox, org.atmosphere.ai.StreamingSession.class, session));

        assertTrue(session.events.stream().anyMatch(e -> e instanceof org.atmosphere.ai.AiEvent.AgentStep),
                "an AgentStep must be streamed per round");
        // Screenshots are streamed as a markdown data-URI image so the Console
        // (which renders message markdown, not typed Content.Image frames) shows them.
        assertTrue(session.sent.stream().anyMatch(
                        s -> s.contains("![shot.png](data:image/png;base64,")),
                "the screenshot must be streamed as a markdown data-URI image");
    }

    @Test
    void executorFailsLoudWhenNoSandboxInjected() {
        ToolExecutor exec = CodeExecTool.definition().executor();
        assertThrows(SandboxException.class,
                () -> exec.execute(Map.of("code", "echo hi"), Map.of()));
    }

    @Test
    void executorRejectsBlankCode() {
        var sandbox = new FakeSandbox(OK);
        ToolExecutor exec = CodeExecTool.definition().executor();
        assertThrows(IllegalArgumentException.class,
                () -> exec.execute(Map.of("code", "  "), Map.of(CodeSandbox.class, sandbox)));
    }

    @Test
    void parseLanguageMapsAliasesAndDefaultsToJavaScript() {
        assertEquals(Language.BASH, CodeExecTool.parseLanguage("bash"));
        assertEquals(Language.BASH, CodeExecTool.parseLanguage("SH"));
        assertEquals(Language.PYTHON, CodeExecTool.parseLanguage("py"));
        assertEquals(Language.JAVASCRIPT, CodeExecTool.parseLanguage("node"));
        assertEquals(Language.JAVASCRIPT, CodeExecTool.parseLanguage(null));
    }

    // --- lazy SessionSandbox --------------------------------------------------

    @Test
    void sessionSandboxProvisionsLazilyAndReusesAcrossCalls() throws Exception {
        var backing = new FakeSandbox(OK);
        var factory = new FakeFactory(true, backing);
        var session = new SessionSandbox(factory, "session-1");

        assertEquals(0, factory.created, "no container until first exec");
        session.exec(SandboxCommand.of(Language.BASH, "echo a"));
        session.exec(SandboxCommand.of(Language.BASH, "echo b"));
        assertEquals(1, factory.created, "sandbox is provisioned once and reused");
    }

    @Test
    void sessionSandboxCloseBeforeUseNeverProvisions() {
        var factory = new FakeFactory(true, new FakeSandbox(OK));
        var session = new SessionSandbox(factory, "session-1");
        session.close();
        assertEquals(0, factory.created);
        assertFalse(session.isReady());
        assertThrows(SandboxException.class,
                () -> session.exec(SandboxCommand.of(Language.BASH, "echo a")));
    }

    @Test
    void sessionSandboxCloseTearsDownBackingSandbox() throws Exception {
        var backing = new FakeSandbox(OK);
        var factory = new FakeFactory(true, backing);
        var session = new SessionSandbox(factory, "session-1");
        session.exec(SandboxCommand.of(Language.BASH, "echo a"));
        session.close();
        assertTrue(backing.closed, "closing the session sandbox closes its backing container");
    }

    // --- CodeExecSupport seam -------------------------------------------------

    @Test
    void supportDisabledWhenFactoryUnavailable() {
        var support = new CodeExecSupport(CodeSandboxFactory.disabled());
        assertFalse(support.isEnabled());
        assertNull(support.install(new CapturingSession(), "s1"),
                "install returns null when disabled so callers can guard with one check");
        assertNotNull(support.tool(), "tool definition is always constructible");
    }

    @Test
    void supportInstallRegistersSandboxAndBindsTeardown() {
        var backing = new FakeSandbox(OK);
        var support = new CodeExecSupport(new FakeFactory(true, backing));
        var session = new CapturingSession();

        var sandbox = support.install(session, "s1");
        assertNotNull(sandbox);
        assertNotNull(session.terminateResource, "teardown must be bound to the session");

        // Driving the session terminal path must close the sandbox.
        try {
            session.terminateResource.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(sandbox.isReady(), "sandbox is closed on session termination");
    }

    @Test
    void withCodeActionLoopLiftsTheToolLoopCeiling() {
        var support = new CodeExecSupport(CodeSandboxFactory.disabled());
        var ctx = new AgentExecutionContext(
                "Hello", "system", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                java.util.List.of(), null, null, java.util.List.of(), new HashMap<>(),
                java.util.List.of(), null, null);
        var lifted = support.withCodeActionLoop(ctx);
        assertEquals(CodeExecSupport.CODE_ACTION_MAX_ROUNDS,
                ToolLoopPolicies.from(lifted).maxIterations());
    }

    // --- test doubles ---------------------------------------------------------

    private static final class FakeSandbox implements CodeSandbox {
        private final SandboxResult result;
        private SandboxCommand last;
        private boolean closed;

        FakeSandbox(SandboxResult result) {
            this.result = result;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean isReady() {
            return !closed;
        }

        @Override
        public SandboxResult exec(SandboxCommand command) {
            this.last = command;
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeFactory implements CodeSandboxFactory {
        private final boolean available;
        private final FakeSandbox sandbox;
        private int created;

        FakeFactory(boolean available, FakeSandbox sandbox) {
            this.available = available;
            this.sandbox = sandbox;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public CodeSandbox create(String sessionId) {
            created++;
            return sandbox;
        }
    }

    /**
     * Minimal StreamingSession double that records the onTerminate registration,
     * emitted events, and streamed content frames.
     */
    private static final class CapturingSession implements org.atmosphere.ai.StreamingSession {
        private AutoCloseable terminateResource;
        private final java.util.List<org.atmosphere.ai.AiEvent> events = new java.util.ArrayList<>();
        private final java.util.List<org.atmosphere.ai.Content> contents = new java.util.ArrayList<>();
        private final java.util.List<String> sent = new java.util.ArrayList<>();

        @Override
        public void onTerminate(AutoCloseable resource) {
            this.terminateResource = resource;
        }

        @Override public void emit(org.atmosphere.ai.AiEvent event) {
            events.add(event);
        }

        @Override public void sendContent(org.atmosphere.ai.Content content) {
            contents.add(content);
        }

        @Override public String sessionId() {
            return "s1";
        }

        @Override public void send(String text) {
            sent.add(text);
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
        }

        @Override public void complete(String summary) {
        }

        @Override public void error(Throwable t) {
        }

        @Override public boolean isClosed() {
            return false;
        }
    }
}
