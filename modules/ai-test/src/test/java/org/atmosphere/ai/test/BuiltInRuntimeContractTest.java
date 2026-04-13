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
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL,
                AiCapability.VISION,
                AiCapability.MULTI_MODAL,
                AiCapability.PROMPT_CACHING,
                AiCapability.PER_REQUEST_RETRY);
    }

    /**
     * Exercise the {@code runtimeWithVisionCapabilityAcceptsImagePart}
     * contract assertion on the Built-in runtime. Built-in declares
     * {@link org.atmosphere.ai.AiCapability#VISION} and threads
     * {@link org.atmosphere.ai.Content.Image} parts through
     * {@code ChatCompletionRequest.parts()} into the OpenAI chat completions
     * multi-content wire format. The assertion checks that
     * {@code runtime.execute(...)} doesn't throw
     * {@code UnsupportedOperationException} at the message-assembly layer;
     * downstream network failures are caught and silently accepted per the
     * base-class assertion contract. Actual wire-format correctness is
     * validated at unit level by
     * {@code OpenAiCompatibleClientMultiModalTest}.
     */
    @Override
    protected AgentExecutionContext createImageContext() {
        var parts = List.<org.atmosphere.ai.Content>of(
                new org.atmosphere.ai.Content.Image(TINY_PNG, "image/png"));
        return new AgentExecutionContext(
                "Describe this image.", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), parts,
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    /**
     * Exercise the {@code runtimeWithPromptCachingAcceptsCacheHint} assertion
     * on the Built-in runtime. {@code buildRequest} reads the hint from
     * {@code context.metadata()} and threads it into
     * {@link org.atmosphere.ai.llm.ChatCompletionRequest#cacheHint()} so
     * {@link org.atmosphere.ai.llm.OpenAiCompatibleClient#buildRequestBody}
     * can emit {@code prompt_cache_key} in the outgoing JSON. The dispatch
     * path fails downstream on a 400 from the remote (no API key in CI) —
     * caught and treated as a skip by the base contract.
     */
    @Override
    protected AgentExecutionContext createCacheContext() {
        var metadata = Map.<String, Object>of(
                org.atmosphere.ai.llm.CacheHint.METADATA_KEY,
                org.atmosphere.ai.llm.CacheHint.conservative("builtin-cache-test"));
        return new AgentExecutionContext(
                "Hello, cached.", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
    }

    /**
     * Exercise {@code runtimeAcceptsCustomRetryPolicyOnContext} on Built-in.
     * The custom {@link org.atmosphere.ai.RetryPolicy#NONE} (zero retries)
     * is threaded through {@code buildRequest} into the
     * {@link org.atmosphere.ai.llm.ChatCompletionRequest#retryPolicy()}
     * field, then read by {@code OpenAiCompatibleClient.sendWithRetry} as
     * a per-request override of the client's instance-level default.
     */
    @Override
    protected AgentExecutionContext createRetryContext() {
        return new AgentExecutionContext(
                "Hello, no retries.", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated())
                .withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE);
    }

    // Built-in execution requires a configured OpenAI API key + remote endpoint.
    // Skip live streaming assertions; capability parity assertions still run.
    @Override
    protected void textStreamingCompletesSession() throws Exception {
        // Skip: requires AiConfig with apiKey + endpoint.
    }
}
