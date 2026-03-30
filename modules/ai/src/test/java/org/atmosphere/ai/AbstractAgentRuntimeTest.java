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

import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AbstractAgentRuntimeTest {

    /** Minimal concrete subclass for testing the base class. */
    static class TestRuntime extends AbstractAgentRuntime<String> {
        boolean doExecuteCalled;
        StreamingSession capturedSession;

        @Override
        public String name() {
            return "test-runtime";
        }

        @Override
        protected String nativeClientClassName() {
            return "java.lang.String";
        }

        @Override
        protected String createNativeClient(AiConfig.LlmSettings settings) {
            return "fake-client";
        }

        @Override
        protected void doExecute(String client, AgentExecutionContext context,
                                 StreamingSession session) {
            doExecuteCalled = true;
            capturedSession = session;
        }

        @Override
        protected String clientDescription() {
            return "TestClient";
        }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
    }

    // -- assembleMessages tests --

    @Test
    void assembleMessagesWithSystemPromptAndHistory() {
        var context = new AgentExecutionContext(
                "current question",
                "You are helpful",
                "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(),
                List.of(
                        new ChatMessage("user", "prev question"),
                        new ChatMessage("assistant", "prev answer")
                ),
                null
        );

        var messages = AbstractAgentRuntime.assembleMessages(context);

        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("You are helpful", messages.get(0).content());
        assertEquals("user", messages.get(1).role());
        assertEquals("prev question", messages.get(1).content());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("prev answer", messages.get(2).content());
        assertEquals("user", messages.get(3).role());
        assertEquals("current question", messages.get(3).content());
    }

    @Test
    void assembleMessagesWithoutSystemPrompt() {
        var context = new AgentExecutionContext(
                "hello",
                null,
                "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(),
                List.of(),
                null
        );

        var messages = AbstractAgentRuntime.assembleMessages(context);

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("hello", messages.get(0).content());
    }

    @Test
    void assembleMessagesWithEmptySystemPrompt() {
        var context = new AgentExecutionContext(
                "hello",
                "",
                "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(),
                List.of(),
                null
        );

        var messages = AbstractAgentRuntime.assembleMessages(context);

        // Empty system prompt should be skipped
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
    }

    @Test
    void assembleMessagesReturnsImmutableList() {
        var context = new AgentExecutionContext(
                "hello",
                "system",
                "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(),
                List.of(),
                null
        );

        var messages = AbstractAgentRuntime.assembleMessages(context);

        assertThrows(UnsupportedOperationException.class, () -> messages.add(ChatMessage.user("nope")));
    }

    // -- Progress event test --

    @Test
    void executeEmitsProgressEvent() {
        var runtime = new TestRuntime();
        runtime.configure(null);

        var progressMessages = new java.util.ArrayList<String>();
        var session = new StreamingSession() {
            @Override
            public String sessionId() {
                return "test";
            }

            @Override
            public void send(String text) {
            }

            @Override
            public void sendMetadata(String key, Object value) {
            }

            @Override
            public void progress(String message) {
                progressMessages.add(message);
            }

            @Override
            public void complete() {
            }

            @Override
            public void complete(String summary) {
            }

            @Override
            public void error(Throwable t) {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        var context = new AgentExecutionContext(
                "hello", null, "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(), List.of(), null
        );

        runtime.execute(context, session);

        assertTrue(runtime.doExecuteCalled);
        assertEquals(1, progressMessages.size());
        assertEquals("Connecting to test-runtime...", progressMessages.get(0));
    }

    // -- Capability tests for BuiltInAgentRuntime --

    @Test
    void builtInRuntimeDeclaresToolCalling() {
        var runtime = new org.atmosphere.ai.llm.BuiltInAgentRuntime();
        assertTrue(runtime.capabilities().contains(AiCapability.TOOL_CALLING));
        assertTrue(runtime.capabilities().contains(AiCapability.TEXT_STREAMING));
        assertTrue(runtime.capabilities().contains(AiCapability.SYSTEM_PROMPT));
    }
}
