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
                null,
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
                null,
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
                null,
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
                null,
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
                java.util.Map.of(), List.of(), null, null
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

    // -- Outer retry wrapper tests (PER_REQUEST_RETRY on framework runtimes) --

    /** Runtime whose doExecute throws the first N times then succeeds. */
    static class FlakyRuntime extends AbstractAgentRuntime<String> {
        final int failuresBeforeSuccess;
        int attempts = 0;

        FlakyRuntime(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override public String name() { return "flaky"; }
        @Override protected String nativeClientClassName() { return "java.lang.String"; }
        @Override protected String createNativeClient(AiConfig.LlmSettings settings) { return "fake"; }
        @Override protected String clientDescription() { return "FakeClient"; }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.PER_REQUEST_RETRY);
        }

        @Override
        protected void doExecute(String client, AgentExecutionContext context, StreamingSession session) {
            attempts++;
            if (attempts <= failuresBeforeSuccess) {
                throw new RuntimeException("transient failure #" + attempts);
            }
        }
    }

    private static StreamingSession noopSession() {
        return new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
        };
    }

    private static AgentExecutionContext contextWithRetry(RetryPolicy policy) {
        return new AgentExecutionContext(
                "hello", null, "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                java.util.Map.of(), List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated(),
                policy
        );
    }

    /**
     * When the caller supplies a non-default RetryPolicy and the runtime
     * does NOT own per-request retry natively, the base class should retry
     * doExecute up to maxRetries times on pre-stream RuntimeException.
     * The policy in this test allows 3 retries; the flaky runtime fails
     * the first 2 attempts and succeeds on the 3rd — so execute() must
     * complete without throwing and the runtime must have attempted 3
     * times total.
     */
    @Test
    void executeRetriesFrameworkRuntimeOnPreStreamFailure() {
        var runtime = new FlakyRuntime(2);
        runtime.configure(null);

        var policy = new RetryPolicy(
                3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5),
                1.0, java.util.Set.of());

        runtime.execute(contextWithRetry(policy), noopSession());

        assertEquals(3, runtime.attempts,
                "outer retry should have retried the flaky doExecute 3 times total (2 failures + 1 success)");
    }

    /**
     * When the retry budget is exhausted, the original exception propagates.
     * Flaky runtime fails 5 times; policy allows only 2 retries (3 total
     * attempts); execute() must throw after 3 attempts.
     */
    @Test
    void executeStopsRetryingAfterBudgetExhausted() {
        var runtime = new FlakyRuntime(5);
        runtime.configure(null);

        var policy = new RetryPolicy(
                2, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5),
                1.0, java.util.Set.of());

        assertThrows(RuntimeException.class,
                () -> runtime.execute(contextWithRetry(policy), noopSession()));
        assertEquals(3, runtime.attempts,
                "flaky runtime should have been attempted exactly maxRetries+1 times (3) before failing");
    }

    /**
     * When the policy is the inheritance sentinel (RetryPolicy.DEFAULT),
     * the outer wrapper is a pass-through and doExecute runs exactly once
     * — no duplicate attempts even if the runtime throws. This preserves
     * the "default means inherit" contract for callers that have not opted
     * into a per-request override.
     */
    @Test
    void executeDoesNotRetryWhenPolicyIsInheritSentinel() {
        var runtime = new FlakyRuntime(99);
        runtime.configure(null);

        // DEFAULT is the inherit sentinel — outer wrapper is pass-through.
        assertThrows(RuntimeException.class,
                () -> runtime.execute(contextWithRetry(RetryPolicy.DEFAULT), noopSession()));
        assertEquals(1, runtime.attempts,
                "inherit-sentinel policy must not trigger outer retries");
    }

    /**
     * Runtimes that own per-request retry natively (Built-in via
     * OpenAiCompatibleClient.sendWithRetry) opt out via
     * {@link AbstractAgentRuntime#ownsPerRequestRetry()} and the outer
     * wrapper becomes a pass-through — no double-retries.
     */
    @Test
    void executeDoesNotWrapWhenRuntimeOwnsRetryNatively() {
        var runtime = new FlakyRuntime(99) {
            @Override protected boolean ownsPerRequestRetry() { return true; }
        };
        runtime.configure(null);

        var policy = new RetryPolicy(
                10, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5),
                1.0, java.util.Set.of());

        assertThrows(RuntimeException.class,
                () -> runtime.execute(contextWithRetry(policy), noopSession()));
        assertEquals(1, runtime.attempts,
                "runtimes that own per-request retry natively must not be wrapped a second time");
    }
}
