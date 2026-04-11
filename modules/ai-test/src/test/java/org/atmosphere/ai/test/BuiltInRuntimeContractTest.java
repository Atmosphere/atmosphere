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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;

import java.util.List;
import java.util.Map;

/**
 * Concrete TCK subclass for {@link BuiltInAgentRuntime}. Lives in
 * {@code modules/ai-test} rather than {@code modules/ai} because
 * {@code modules/ai-test} already owns the {@link AbstractAgentRuntimeContractTest}
 * base class and depends on {@code modules/ai} — putting this subclass in
 * {@code modules/ai} would require a reverse dependency. Brings the built-in
 * runtime into the cross-runtime parity matrix so assertions like
 * {@code runtimeWithSystemPromptAlsoDeclaresStructuredOutput} and
 * {@code hitlPendingApprovalEmitsProtocolEvent} actually run against it
 * instead of silently skipping.
 */
class BuiltInRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        return new BuiltInAgentRuntime();
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return null;
    }

    // Built-in execution requires a configured OpenAI API key + remote endpoint.
    // Skip live streaming assertions; capability parity assertions still run.
    @Override
    protected void textStreamingCompletesSession() throws Exception {
        // Skip: requires AiConfig with apiKey + endpoint.
    }
}
