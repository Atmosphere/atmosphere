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

import com.microsoft.semantickernel.aiservices.openai.chatcompletion.OpenAIChatCompletion;
import com.microsoft.semantickernel.exceptions.SKException;
import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.ApprovalTimedOutException;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A request that carries a tool, driven through the real Semantic Kernel
 * {@link OpenAIChatCompletion} against a local OpenAI-compatible server: the
 * tool reaches the model, runs through Atmosphere's tool path, and its result
 * reaches the final answer; a tool round that ended the turn calls the model no
 * more; a failure before the first model call ends the session with an error.
 */
class SemanticKernelToolCallingTest {

    private static final String TOOL_CALL_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"atmosphere_tools-add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}}],
             "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
            """;

    private static final String ANSWER_RESPONSE = """
            {"id":"chatcmpl-2","object":"chat.completion","created":1700000001,"model":"gpt-4o-mini",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
               "content":"The sum is 5."}}],
             "usage":{"prompt_tokens":23,"completion_tokens":5,"total_tokens":28}}
            """;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    /** What the server answers once {@link #responses} is drained. */
    private volatile String fallback = ANSWER_RESPONSE;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var next = responses.poll();
                var body = (next != null ? next : fallback).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void oneToolRoundReachesTheModelAndItsResultTheAnswer() {
        responses.add(TOOL_CALL_RESPONSE);
        responses.add(ANSWER_RESPONSE);
        var ran = new AtomicInteger();
        var session = new RecordingSession();
        var tool = addTool(args -> {
            ran.incrementAndGet();
            return String.valueOf(toInt(args.get("a")) + toInt(args.get("b")));
        });

        runtime().execute(context(tool), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the turn ends");
        assertNull(session.collecting.failure(), "the turn ends without an error");
        assertEquals(1, ran.get(), "the tool ran once");
        assertEquals(2, requests.size(), "a tool round and the answer round");
        assertTrue(requests.get(0).replace(" ", "").contains("\"name\":\"add\""),
                "the tool reached the model: " + requests.get(0));
        assertTrue(requests.get(1).contains("\"role\":\"tool\"") && requests.get(1).contains("\"5\""),
                "the tool's result reached the model: " + requests.get(1));
        assertEquals("The sum is 5.", session.collecting.text(), "the answer reached the session");
        assertTrue(session.events.stream().anyMatch(e -> e instanceof AiEvent.ToolStart start
                && "add".equals(start.toolName())), "a ToolStart frame: " + session.events);
        assertTrue(session.events.stream().anyMatch(e -> e instanceof AiEvent.ToolResult result
                && "add".equals(result.toolName())), "a ToolResult frame: " + session.events);
        assertTrue(session.usage.stream().anyMatch(u -> u.total() == 28L),
                "the answer round's token usage: " + session.usage);
    }

    /**
     * Semantic Kernel names a tool {@code <plugin>-<function>}; on Ollama with
     * qwen2.5 the hyphenated name made the model's tool call unparseable and
     * the turn ended with nothing. The tools go out under their own names, the
     * model calls them by those names, and the call still reaches the tool;
     * the follow-up round's history carries the same names.
     */
    @Test
    void toolsReachTheModelUnderTheirOwnNamesAndTheModelsCallsReachThem() {
        responses.add(TOOL_CALL_RESPONSE.replace("atmosphere_tools-add", "add"));
        responses.add(ANSWER_RESPONSE);
        var ran = new AtomicInteger();
        var session = new RecordingSession();

        runtime().execute(context(addTool(args -> {
            ran.incrementAndGet();
            return String.valueOf(toInt(args.get("a")) + toInt(args.get("b")));
        })), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the turn ends");
        assertNull(session.collecting.failure(), "the turn ends without an error");
        assertEquals(1, ran.get(), "the model's call by the tool's own name ran the tool");
        assertEquals(2, requests.size(), "a tool round and the answer round: " + requests);
        var first = requests.get(0).replace(" ", "");
        assertTrue(first.contains("\"name\":\"add\""), "the tool goes out as 'add': " + first);
        assertFalse(first.contains("atmosphere_tools"), "no plugin prefix on the wire: " + first);
        var second = requests.get(1).replace(" ", "");
        assertTrue(second.contains("\"tool_calls\":[") && second.contains("\"name\":\"add\""),
                "the history's tool call carries the tool's own name: " + second);
        assertFalse(second.contains("atmosphere_tools"), "no plugin prefix in the history: " + second);
        assertEquals("The sum is 5.", session.collecting.text(), "the answer reached the session");
    }

    /**
     * A model that ends a tool turn with no text and no tool call: the other
     * runtimes complete such a turn (the built-in one included), so this one
     * does too, but it says so in the log with what tells a dropped tool call
     * apart: the completion tokens the model spent and the tools it was given.
     */
    @Test
    void turnThatEndsWithNoTextAndNoToolCallIsLoggedNotSilent() {
        responses.add("""
                {"id":"chatcmpl-3","object":"chat.completion","created":1700000002,"model":"qwen2.5",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":""}}],
                 "usage":{"prompt_tokens":1704,"completion_tokens":40,"total_tokens":1744}}
                """);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                SemanticKernelStreamingAdapter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var session = new RecordingSession();

            runtime().execute(context(addTool(args -> "5")), session);

            assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the turn ends");
            assertNull(session.collecting.failure(), "an empty answer ends the turn as on the other runtimes");
            assertTrue(session.collecting.text().isEmpty(), "no answer: " + session.collecting.text());
            var warning = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .findFirst()
                    .orElse(null);
            assertNotNull(warning, "the empty turn is logged: " + appender.list);
            assertTrue(warning.contains("40") && warning.contains("add"),
                    "the warning names the completion tokens and the tools: " + warning);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * Semantic Kernel builds the schema of every function of the kernel on each
     * model call and resolves the type of each parameter through its own type
     * table, which knows no {@code number} and no {@code object}: one tool with
     * such a parameter used to fail every tool turn of the endpoint before the
     * model call ("Requested type could not be found: number").
     */
    @Test
    void toolsWithEveryJsonParameterTypeReachTheModelAndRun() {
        responses.add("""
                {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_1","type":"function",
                     "function":{"name":"atmosphere_tools-convert",
                       "arguments":"{\\"value\\":21.5,\\"unit\\":\\"C\\",\\"count\\":2,\\"exact\\":true,\\"options\\":{\\"round\\":1},\\"tags\\":[\\"a\\"]}"}}]}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                """);
        responses.add(ANSWER_RESPONSE);
        var received = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var convert = ToolDefinition.builder("convert", "Convert a temperature")
                .parameter("value", "The temperature", "number")
                .parameter("unit", "The unit", "string")
                .parameter("count", "How many", "integer")
                .parameter("exact", "Exact or not", "boolean")
                .parameter("options", "Options", "object")
                .parameter("tags", "Tags", "array")
                .executor(args -> {
                    received.set(Map.copyOf(args));
                    return "70.7";
                })
                .build();
        var weather = ToolDefinition.builder("get_weather", "The weather of a city")
                .parameter("city", "The city", "string")
                .executor(args -> "sunny")
                .build();
        var session = new RecordingSession();

        runtime().execute(new AgentExecutionContext(
                "convert 21.5 C", null, "gpt-4o-mini", null, "session-1", "user", "conv-1",
                List.of(convert, weather), null, null, List.of(), Map.of(), List.of(), null, null), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the turn ends");
        assertNull(session.collecting.failure(), "the turn ends without an error");
        assertEquals("The sum is 5.", session.collecting.text(), "the answer reached the session");
        assertEquals(2, requests.size(), "a tool round and the answer round");
        var first = requests.get(0).replace(" ", "");
        assertTrue(first.contains("\"value\":{\"type\":\"number\""), "number advertised: " + first);
        assertTrue(first.contains("\"count\":{\"type\":\"integer\""), "integer advertised: " + first);
        assertTrue(first.contains("\"exact\":{\"type\":\"boolean\""), "boolean advertised: " + first);
        assertTrue(first.contains("\"unit\":{\"type\":\"string\""), "string advertised: " + first);
        assertTrue(first.contains("\"tags\":{\"type\":\"array\""), "array advertised: " + first);
        var options = first.substring(first.indexOf("\"options\":{") + "\"options\":".length());
        assertTrue(options.substring(0, options.indexOf('}')).contains("\"type\":\"object\""),
                "object advertised: " + first);
        assertTrue(first.contains("get_weather"), "the other tool reached the model too: " + first);
        var args = received.get();
        assertNotNull(args, "the tool ran");
        assertEquals(21.5d, args.get("value"), "a number argument arrives as a Double: " + args);
        assertEquals(2L, args.get("count"), "an integer argument arrives as a Long: " + args);
        assertEquals(Boolean.TRUE, args.get("exact"), "a boolean argument arrives as a Boolean: " + args);
        assertEquals("C", args.get("unit"), "a string argument arrives as it is: " + args);
    }

    @Test
    void toolRoundThatEndedTheTurnMakesNoFollowUpModelCall() throws Exception {
        responses.add(TOOL_CALL_RESPONSE);
        responses.add(ANSWER_RESPONSE);
        var session = new RecordingSession();
        var tool = addTool(args -> {
            session.error(new ApprovalTimedOutException(
                    "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"));
            return "{\"status\":\"timeout\"}";
        });

        runtime().execute(context(tool), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the tool round ended the turn");
        assertInstanceOf(ApprovalTimedOutException.class, session.collecting.failure(),
                "the turn ends with the approval that ran out, not a later error");
        // The follow-up, were it sent, runs right after the tool on SK's loop;
        // give it the time to land before counting.
        Thread.sleep(500);
        assertEquals(1, requests.size(), "no follow-up model call once the turn ended");
    }

    @Test
    void turnCancelledInAToolRoundEndsWithoutAnErrorAndNoFollowUpCall() throws Exception {
        responses.add(TOOL_CALL_RESPONSE);
        responses.add(ANSWER_RESPONSE);
        var session = new RecordingSession();
        var tool = addTool(args -> {
            // The cancel lands while the tool runs; the tool returns normally.
            session.turnCancelled = true;
            return "5";
        });

        runtime().execute(context(tool), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the cancelled turn ends");
        assertNull(session.collecting.failure(), "a cancelled turn is not an error");
        Thread.sleep(500);
        assertEquals(1, requests.size(), "no follow-up model call once the turn was cancelled");
    }

    @Test
    void turnCancelledBeforeItStartsCallsNoModelAndEndsWithoutAnError() {
        var session = new RecordingSession();
        session.turnCancelled = true;

        runtime().execute(context(addTool(args -> "5")), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the cancelled turn ends");
        assertNull(session.collecting.failure(), "a cancelled turn is not an error");
        assertEquals(0, requests.size(), "no model call for a turn cancelled before it started");
    }

    @Test
    void toolLoopOutOfAutoInvokeAttemptsEndsTheTurnWithAnError() {
        // A model that asks for the tool on every round: SK's loop stops
        // invoking at its cap and returns the last round, still asking.
        fallback = TOOL_CALL_RESPONSE;
        var session = new RecordingSession();

        runtime().execute(context(addTool(args -> "5")), session);

        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the turn ends");
        assertInstanceOf(IllegalStateException.class, session.collecting.failure(),
                "an exhausted loop is an error, not an empty answer");
        assertTrue(session.collecting.text().isEmpty(), "no answer: " + session.collecting.text());
        assertTrue(requests.size() > 1 && requests.size() <= 10,
                "SK's loop is bounded: " + requests.size() + " model calls");
    }

    @Test
    void toolRequestAgainstTheRealServiceNeverLeavesTheSessionOpen() {
        // The streaming call SK 1.5.0 refuses with auto-invoke on: whatever path
        // the runtime takes, the session ends (with the answer, or an error).
        responses.add(ANSWER_RESPONSE);
        var session = new RecordingSession();
        try {
            runtime().execute(context(addTool(args -> "5")), session);
        } catch (RuntimeException e) {
            // The session's state is what is asserted.
        }
        assertTrue(session.collecting.await(Duration.ofSeconds(10)), "the session ends");
    }

    @Test
    void failureBeforeTheModelCallEndsTheSessionWithTheError() {
        var service = mock(ChatCompletionService.class);
        var refused = new SKException("refused before any model call");
        when(service.getStreamingChatMessageContentsAsync(any(ChatHistory.class), any(), any()))
                .thenThrow(refused);
        when(service.getChatMessageContentsAsync(any(ChatHistory.class), any(), any()))
                .thenThrow(refused);
        for (var tools : List.of(List.<ToolDefinition>of(), List.of(addTool(args -> "5")))) {
            var session = new CollectingSession();
            try {
                new TestableRuntime(service).execute(new AgentExecutionContext(
                        "add 2 and 3", null, "gpt-4o-mini", null, "session-1", "user", "conv-1",
                        tools, null, null, List.of(), Map.of(), List.of(), null, null), session);
            } catch (RuntimeException e) {
                // The session's state is what is asserted.
            }
            assertTrue(session.isClosed(), "the session ends (tools=" + tools.size() + ")");
            assertNotNull(session.failure(), "with the error (tools=" + tools.size() + ")");
        }
    }

    private TestableRuntime runtime() {
        var client = SemanticKernelOpenAiClientFactory.forEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key");
        return new TestableRuntime(OpenAIChatCompletion.builder()
                .withOpenAIAsyncClient(client)
                .withModelId("gpt-4o-mini")
                .build());
    }

    private static AgentExecutionContext context(ToolDefinition tool) {
        return new AgentExecutionContext(
                "add 2 and 3", null, "gpt-4o-mini", null, "session-1", "user", "conv-1",
                List.of(tool), null, null, List.of(), Map.of(), List.of(), null, null);
    }

    private static ToolDefinition addTool(org.atmosphere.ai.tool.ToolExecutor executor) {
        return ToolDefinition.builder("add", "Add two numbers")
                .parameter("a", "First", "integer")
                .parameter("b", "Second", "integer")
                .executor(executor)
                .build();
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /** Injects the {@link ChatCompletionService} as the runtime's native client. */
    private static final class TestableRuntime extends SemanticKernelAgentRuntime {
        TestableRuntime(ChatCompletionService service) {
            setNativeClient(service);
        }
    }

    /** A {@link CollectingSession} that also keeps the frames and usage it was given. */
    private static final class RecordingSession implements StreamingSession {

        private final CollectingSession collecting = new CollectingSession();
        private final List<AiEvent> events = new CopyOnWriteArrayList<>();
        private final List<TokenUsage> usage = new CopyOnWriteArrayList<>();
        private volatile boolean turnCancelled;

        @Override
        public boolean isTurnCancelled() {
            return turnCancelled;
        }

        @Override
        public String sessionId() {
            return collecting.sessionId();
        }

        @Override
        public void send(String text) {
            collecting.send(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            collecting.sendMetadata(key, value);
        }

        @Override
        public void progress(String message) {
            collecting.progress(message);
        }

        @Override
        public void complete() {
            collecting.complete();
        }

        @Override
        public void complete(String summary) {
            collecting.complete(summary);
        }

        @Override
        public void error(Throwable t) {
            collecting.error(t);
        }

        @Override
        public boolean isClosed() {
            return collecting.isClosed();
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
            StreamingSession.super.emit(event);
        }

        @Override
        public void usage(TokenUsage tokenUsage) {
            usage.add(tokenUsage);
        }
    }
}
