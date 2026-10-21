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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import com.microsoft.semantickernel.services.chatcompletion.StreamingChatContent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concrete TCK subclass for {@link SemanticKernelAgentRuntime}. Previously
 * SK had a freestanding test that did not run the cross-runtime parity
 * assertions ({@code runtimeWithSystemPromptAlsoDeclaresStructuredOutput},
 * {@code hitlPendingApprovalEmitsProtocolEvent}, etc.). This subclass
 * brings SK into the contract test matrix so those assertions actually
 * fire against 6/6 runtimes instead of silently skipping.
 *
 * <p>Execution-level tests override to skip because SK requires a real
 * {@code ChatCompletionService} backed by an Azure {@code OpenAIAsyncClient},
 * which this module's unit dependencies do not carry.</p>
 */
class SemanticKernelRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected Object bindApplicationClient(org.atmosphere.ai.AiEnvironment environment) {
        var client = org.mockito.Mockito.mock(com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService.class);
        SemanticKernelAgentRuntime.setChatCompletionService(environment, client);
        return client;
    }

    @Override
    protected AgentRuntime createRuntime() {
        @SuppressWarnings("unchecked")
        var frame = (StreamingChatContent<Object>) mock(StreamingChatContent.class);
        when(frame.getContent()).thenReturn("Hello world");
        when(frame.getMetadata()).thenReturn(null);

        var service = mock(ChatCompletionService.class);
        when(service.getStreamingChatMessageContentsAsync(
                any(ChatHistory.class), any(), any()))
                .thenAnswer(inv -> {
                    ChatHistory history = inv.getArgument(0);
                    if (carriesErrorSentinel(history)) {
                        return Flux.error(new RuntimeException("forced contract error"));
                    }
                    return Flux.just(frame);
                });

        return new TestableSemanticKernelRuntime(service);
    }

    private static boolean carriesErrorSentinel(ChatHistory history) {
        for (var m : history.getMessages()) {
            var content = m.getContent();
            if (content != null && content.contains(CONTRACT_ERROR_SENTINEL)) {
                return true;
            }
        }
        return false;
    }

    /** The local OpenAI-compatible server the tool-loop fixture's real SK service talks to. */
    private static com.sun.net.httpserver.HttpServer toolLoopServer;

    @org.junit.jupiter.api.AfterAll
    static void stopToolLoopServer() {
        if (toolLoopServer != null) {
            toolLoopServer.stop(0);
        }
    }

    /**
     * Semantic Kernel runs its auto-invoke loop on the non-streaming call, so
     * the fixture drives the real {@code OpenAIChatCompletion} against a local
     * OpenAI-compatible server: a request with no tool result yet is answered
     * with a {@code lookup} tool call (SK's loop runs the tool through the
     * runtime's bridge), and the request that carries the tool result is
     * answered 300 ms later, from the server's thread, with the final text.
     */
    @Override
    protected ToolLoopFixture createToolLoopFixture() {
        var client = SemanticKernelOpenAiClientFactory.forEndpoint(
                "http://127.0.0.1:" + toolLoopServer().getAddress().getPort() + "/v1", "test-key");
        var service = com.microsoft.semantickernel.aiservices.openai.chatcompletion.OpenAIChatCompletion.builder()
                .withOpenAIAsyncClient(client)
                .withModelId("gpt-4o-mini")
                .build();
        return new ToolLoopFixture(new TestableSemanticKernelRuntime(service),
                toolLoopContext("gpt-4o-mini"), TOOL_LOOP_ANSWER);
    }

    private static synchronized com.sun.net.httpserver.HttpServer toolLoopServer() {
        if (toolLoopServer != null) {
            return toolLoopServer;
        }
        try {
            var server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var request = new String(exchange.getRequestBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    String body;
                    if (request.contains("\"role\":\"tool\"")) {
                        Thread.sleep(300);
                        body = """
                                {"id":"chatcmpl-2","object":"chat.completion","created":1700000001,
                                 "model":"gpt-4o-mini","choices":[{"index":0,"finish_reason":"stop",
                                   "message":{"role":"assistant","content":"%s"}}],
                                 "usage":{"prompt_tokens":20,"completion_tokens":6,"total_tokens":26}}
                                """.formatted(TOOL_LOOP_ANSWER);
                    } else {
                        body = """
                                {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,
                                 "model":"gpt-4o-mini","choices":[{"index":0,"finish_reason":"tool_calls",
                                   "message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1",
                                     "type":"function","function":{"name":"atmosphere_tools-lookup",
                                       "arguments":"{\\"city\\":\\"Paris\\"}"}}]}}],
                                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                                """;
                    }
                    var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            toolLoopServer = server;
            return server;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static class TestableSemanticKernelRuntime extends SemanticKernelAgentRuntime {
        TestableSemanticKernelRuntime(ChatCompletionService service) {
            setNativeClient(service);
        }
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
        // createRuntime()'s mocked service has no tool loop. The tool round runs
        // against the real SK service in createToolLoopFixture() and in
        // SemanticKernelToolCallingTest (tool result, turn end, failure paths).
        return null;
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return new AgentExecutionContext(
                CONTRACT_ERROR_SENTINEL, "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /**
     * {@code TEXT_STREAMING} holds for a turn without tools: SK 1.5.0 runs its
     * auto-invoke tool loop only on the non-streaming call, so a turn with
     * tools answers in one piece ({@code modules/ai/README.md} capability
     * matrix). {@code TOOL_CALLING} is proven against the real
     * {@code OpenAIChatCompletion} by {@link #createToolLoopFixture()} and
     * {@code SemanticKernelToolCallingTest}.
     */
    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.NATIVE_STRUCTURED_OUTPUT,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.TOKEN_USAGE,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION,
                AiCapability.VISION,
                AiCapability.MULTI_MODAL,
                AiCapability.CANCELLATION);
    }

    /**
     * All four knobs ride SK's {@code PromptExecutionSettings} builder
     * ({@code withTemperature}/{@code withMaxTokens}/{@code withTopP}/
     * {@code withStopSequences}) on the default {@code InvocationContext};
     * a per-request {@code SemanticKernelInvocation} override takes full
     * control and bypasses them by design. Proven in
     * {@code SemanticKernelGenerationParamsTest}.
     */
    @Override
    protected Set<GenerationParamsSupport> expectedGenerationHonoring() {
        return Set.of(GenerationParamsSupport.TEMPERATURE,
                GenerationParamsSupport.MAX_TOKENS,
                GenerationParamsSupport.TOP_P,
                GenerationParamsSupport.STOP);
    }

    /**
     * Both entries point at dedicated tests in this module that assert more
     * than the TCK's hooks could:
     * <ul>
     *   <li>{@code VISION} — {@link SemanticKernelVisionWireShapeTest} pins the
     *       native multi-modal content shape on the outgoing chat history.</li>
     *   <li>{@code CANCELLATION} — {@link SemanticKernelAgentRuntimeCancelTest}
     *       drives {@code doExecuteWithHandle} against a {@code doOnCancel}-
     *       instrumented {@link Flux} and asserts the Reactor subscription is
     *       disposed and the session completed exactly once.</li>
     * </ul>
     */
    @Override
    protected Map<AiCapability, String> capabilitiesCoveredOutsideTck() {
        return Map.of(
                AiCapability.VISION, "SemanticKernelVisionWireShapeTest",
                AiCapability.CANCELLATION, "SemanticKernelAgentRuntimeCancelTest");
    }

    /**
     * Exercise {@code runtimeAcceptsCustomRetryPolicyOnContext}. Semantic
     * Kernel inherits {@code AbstractAgentRuntime.executeWithOuterRetry},
     * which wraps {@code doExecute} when the context carries a non-inherit
     * policy.
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

}
