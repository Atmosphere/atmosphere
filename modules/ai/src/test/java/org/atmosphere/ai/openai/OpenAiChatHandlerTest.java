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
package org.atmosphere.ai.openai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiChatHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OpenAiServing ENABLED =
            new OpenAiServing(true, Map.of(), null, null);

    /** Captures the execution context and delegates streaming to a scripted behavior. */
    static final class StubRuntime implements AgentRuntime {

        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();
        private final BiConsumer<AgentExecutionContext, StreamingSession> behavior;

        StubRuntime(BiConsumer<AgentExecutionContext, StreamingSession> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            behavior.accept(context, session);
        }
    }

    record Rig(AtmosphereResource resource, StringWriter atmosphereOutput,
               StringWriter servletOutput, AtmosphereResponse response) {
    }

    private static Rig rig(String method, String uri, String contentType,
                           String authorization, String body) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("test-uuid");
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContentType()).thenReturn(contentType);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        var atmosphereOutput = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(atmosphereOutput));
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        var servletOutput = new StringWriter();
        when(servletResponse.getWriter()).thenReturn(new PrintWriter(servletOutput));
        when(response.getResponse()).thenReturn(servletResponse);
        return new Rig(resource, atmosphereOutput, servletOutput, response);
    }

    private static Rig completionsRig(String body) throws Exception {
        return rig("POST", OpenAiServing.CHAT_COMPLETIONS_PATH,
                "application/json", null, body);
    }

    private static AiPipeline pipeline(AgentRuntime runtime,
                                       InMemoryConversationMemory memory) {
        return new AiPipeline(runtime, "You are a governed test agent.", "stub-model",
                memory, null, List.of(), List.of(), null);
    }

    private static OpenAiChatHandler handler(AgentRuntime runtime,
                                             InMemoryConversationMemory memory) {
        var handler = new OpenAiChatHandler(ENABLED);
        handler.register("demo", pipeline(runtime, memory), memory);
        return handler;
    }

    @Test
    void nonStreamingCompletionWrapsPipelineResultInEnvelope() throws Exception {
        var runtime = new StubRuntime((context, session) -> {
            session.send("Hello ");
            session.send("world");
            session.usage(TokenUsage.of(3, 5));
            session.complete("Hello world");
        });
        var rig = completionsRig(
                "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(200);
        verify(rig.response()).setContentType("application/json");
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals("chat.completion", node.get("object").stringValue());
        assertEquals("demo", node.get("model").stringValue());
        assertEquals("Hello world",
                node.get("choices").get(0).get("message").get("content").stringValue());
        assertEquals("stop", node.get("choices").get(0).get("finish_reason").stringValue());
        assertEquals(8L, node.get("usage").get("total_tokens").asLong());
    }

    @Test
    void streamingCompletionEmitsChunksUsageAndDone() throws Exception {
        var runtime = new StubRuntime((context, session) -> {
            session.send("Hel");
            session.send("lo");
            session.usage(TokenUsage.of(2, 4));
            session.complete("Hello");
        });
        var rig = completionsRig("{\"model\":\"demo\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        var frames = rig.servletOutput().toString().split("\n\n");
        assertTrue(frames.length >= 5, "expected role+content+finish+usage+DONE frames");
        var role = MAPPER.readTree(frames[0].substring("data: ".length()));
        assertEquals("chat.completion.chunk", role.get("object").stringValue());
        assertEquals("assistant",
                role.get("choices").get(0).get("delta").get("role").stringValue());
        var first = MAPPER.readTree(frames[1].substring("data: ".length()));
        assertEquals("Hel",
                first.get("choices").get(0).get("delta").get("content").stringValue());
        var finish = MAPPER.readTree(frames[3].substring("data: ".length()));
        assertEquals("stop",
                finish.get("choices").get(0).get("finish_reason").stringValue());
        var usage = MAPPER.readTree(frames[4].substring("data: ".length()));
        assertEquals(6L, usage.get("usage").get("total_tokens").asLong());
        assertEquals("data: [DONE]", frames[frames.length - 1].strip());
    }

    @Test
    void streamingSummaryOnlyRuntimeStillYieldsOneContentChunk() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete("Summary only"));
        var rig = completionsRig("{\"model\":\"demo\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        var output = rig.servletOutput().toString();
        assertTrue(output.contains("Summary only"));
        assertTrue(output.strip().endsWith("data: [DONE]"));
    }

    @Test
    void malformedJsonReturns400ErrorEnvelope() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = completionsRig("not json {{{");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(400);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals(OpenAiError.TYPE_INVALID_REQUEST,
                node.get("error").get("type").stringValue());
    }

    @Test
    void unknownModelReturns404ModelNotFound() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = completionsRig(
                "{\"model\":\"nope\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(404);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals("model_not_found", node.get("error").get("code").stringValue());
    }

    @Test
    void toolsParameterReturns400Unsupported() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = completionsRig("{\"model\":\"demo\",\"tools\":[{\"type\":\"function\"}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(400);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals("unsupported_parameter", node.get("error").get("code").stringValue());
        assertEquals("tools", node.get("error").get("param").stringValue());
    }

    @Test
    void nonJsonContentTypeReturns415() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = rig("POST", OpenAiServing.CHAT_COMPLETIONS_PATH, "text/plain", null,
                "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(415);
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = rig("PUT", OpenAiServing.CHAT_COMPLETIONS_PATH, "application/json", null, "{}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(405);
    }

    @Test
    void guardrailStyleSecurityFailureReturns403RequestBlocked() throws Exception {
        var runtime = new StubRuntime((context, session) ->
                session.error(new SecurityException("Request blocked: off-topic")));
        var rig = completionsRig(
                "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(403);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals("request_blocked", node.get("error").get("code").stringValue());
    }

    @Test
    void configuredApiKeyGatesTheEndpoint() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete("ok"));
        var serving = new OpenAiServing(true, Map.of(), null, "sk-test");
        var handler = new OpenAiChatHandler(serving);
        handler.register("demo", pipeline(runtime, null), null);

        var denied = rig("POST", OpenAiServing.CHAT_COMPLETIONS_PATH, "application/json",
                null, "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
        handler.onRequest(denied.resource());
        verify(denied.response()).setStatus(401);
        assertEquals("invalid_api_key", MAPPER.readTree(denied.atmosphereOutput().toString())
                .get("error").get("code").stringValue());

        var allowed = rig("POST", OpenAiServing.CHAT_COMPLETIONS_PATH, "application/json",
                "Bearer sk-test",
                "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
        handler.onRequest(allowed.resource());
        verify(allowed.response()).setStatus(200);
    }

    @Test
    void modelsListingReturnsRegisteredAgents() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var rig = rig("GET", OpenAiServing.MODELS_PATH, null, null, "");

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(200);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals("list", node.get("object").stringValue());
        assertEquals("demo", node.get("data").get(0).get("id").stringValue());
    }

    @Test
    void priorTurnsThreadThroughConversationMemoryAndAreClearedAfter() throws Exception {
        var memory = new InMemoryConversationMemory(20);
        var runtime = new StubRuntime((context, session) -> session.complete("done"));
        var rig = completionsRig("""
                {"model":"demo","messages":[
                  {"role":"system","content":"Client context."},
                  {"role":"user","content":"First question"},
                  {"role":"assistant","content":"First answer"},
                  {"role":"user","content":"Follow-up"}]}""");

        handler(runtime, memory).onRequest(rig.resource());

        var context = runtime.lastContext.get();
        assertEquals("Follow-up", context.message());
        // The agent's own system prompt stays authoritative — the client
        // system message rides along as history, it does not replace it.
        assertTrue(context.systemPrompt().contains("You are a governed test agent."));
        assertEquals(3, context.history().size());
        assertEquals("system", context.history().get(0).role());
        assertEquals("Client context.", context.history().get(0).content());
        assertEquals("First question", context.history().get(1).content());
        assertEquals("assistant", context.history().get(2).role());
        // Terminal path completeness: the per-request conversation key is
        // cleared so staged history can never leak across requests.
        assertTrue(memory.getHistory(context.conversationId()).isEmpty());
    }

    /**
     * Regression (registre#6): with no conversation memory on the binding
     * (the default — {@code @AiEndpoint.conversationMemory()} is false),
     * the handler used to dispatch only the final user message: an OpenAI
     * SDK client sending a system prompt plus history got a confident 200
     * from a model that saw neither. The client's context now threads
     * through the request itself.
     */
    @Test
    void priorTurnsReachTheModelEvenWithoutConversationMemory() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete("done"));
        var rig = completionsRig("""
                {"model":"demo","messages":[
                  {"role":"system","content":"Client context."},
                  {"role":"user","content":"First question"},
                  {"role":"assistant","content":"First answer"},
                  {"role":"user","content":"Follow-up"}]}""");

        handler(runtime, null).onRequest(rig.resource());

        var context = runtime.lastContext.get();
        assertEquals("Follow-up", context.message());
        assertEquals(3, context.history().size(),
                "the client-sent history must reach the model without memory: "
                        + context.history());
        assertEquals("Client context.", context.history().get(0).content());
        assertEquals("system", context.history().get(0).role());
        assertEquals("assistant", context.history().get(2).role());
        // The agent's own system prompt stays authoritative.
        assertTrue(context.systemPrompt().contains("You are a governed test agent."));
        // The transport key is consumed — it must not leak to the provider.
        assertTrue(!context.metadata().containsKey(
                        org.atmosphere.ai.AiPipeline.REQUEST_HISTORY_METADATA_KEY),
                "the history metadata key must be consumed before the provider "
                        + "sees the request: " + context.metadata().keySet());
    }

    @Test
    void resolveTargetHonorsWhitelistDefaultAndExactMatch() {
        var mapped = new OpenAiServing(true, Map.of("gpt-alias", "demo"), null, null);
        assertEquals("demo",
                OpenAiChatHandler.resolveTarget("gpt-alias", mapped, Set.of("demo", "other")));
        // A non-empty map is a whitelist: even registered names are refused.
        assertEquals(404, assertThrows(OpenAiError.class, () ->
                OpenAiChatHandler.resolveTarget("other", mapped, Set.of("demo", "other")))
                .status());

        var open = new OpenAiServing(true, Map.of(), null, null);
        assertEquals("demo", OpenAiChatHandler.resolveTarget("demo", open, Set.of("demo")));
        assertEquals(404, assertThrows(OpenAiError.class, () ->
                OpenAiChatHandler.resolveTarget("nope", open, Set.of("demo"))).status());
        assertEquals(400, assertThrows(OpenAiError.class, () ->
                OpenAiChatHandler.resolveTarget(null, open, Set.of("demo"))).status());

        var fallback = new OpenAiServing(true, Map.of(), "demo", null);
        assertEquals("demo", OpenAiChatHandler.resolveTarget("anything", fallback, Set.of()));
        assertEquals("demo", OpenAiChatHandler.resolveTarget(null, fallback, Set.of()));
    }

    @Test
    void unavailableMappedAgentReturns503() throws Exception {
        var serving = new OpenAiServing(true, Map.of("alias", "missing-agent"), null, null);
        var handler = new OpenAiChatHandler(serving);
        var rig = completionsRig(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler.onRequest(rig.resource());

        verify(rig.response()).setStatus(503);
        var node = MAPPER.readTree(rig.atmosphereOutput().toString());
        assertEquals(OpenAiError.TYPE_SERVER_ERROR, node.get("error").get("type").stringValue());
    }

    @Test
    void oversizedBodyReturns413() throws Exception {
        var runtime = new StubRuntime((context, session) -> session.complete());
        var big = "x".repeat(OpenAiChatHandler.MAX_BODY_CHARS + 1);
        var rig = completionsRig(big);

        handler(runtime, null).onRequest(rig.resource());

        verify(rig.response()).setStatus(413);
        assertFalse(rig.atmosphereOutput().toString().isEmpty());
    }
}
