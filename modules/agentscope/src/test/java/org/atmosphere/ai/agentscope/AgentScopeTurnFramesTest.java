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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.NativeStructuredOutput;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one AgentScope turn puts on the wire: each answer once, however the
 * agent reports it. The real per-request {@link ReActAgent} runs over a
 * scripted model.
 */
class AgentScopeTurnFramesTest {

    @Test
    void aStreamedAnswerReachesTheSessionOnce() {
        var session = run(new ScriptedModel(List.of(List.of(text("Hel"), text("lo")))), List.of());

        assertEquals(List.of("Hel", "lo"), session.sends);
    }

    @Test
    void anAnswerInOneChunkReachesTheSessionOnce() {
        var session = run(new ScriptedModel(List.of(List.of(text("Hello")))), List.of());

        assertEquals(List.of("Hello"), session.sends);
    }

    @Test
    void theAnswerAfterAToolRoundReachesTheSessionOnce() {
        var ran = new AtomicInteger();
        var model = new ScriptedModel(List.of(
                List.of(text("Checking. "), toolUse("call-1", "convert", "{\"celsius\":5}", Map.of("celsius", 5))),
                List.of(text("It is "), text("41F."))));

        var session = run(model, List.of(convert(ran)));

        assertEquals(1, ran.get(), "the tool ran");
        assertEquals(List.of("Checking. ", "It is ", "41F."), session.sends);
        assertEquals(List.of("ToolStart:convert", "ToolResult:convert"), session.toolFrames());
    }

    @Test
    void eachRoundReportsItsTokenUsageOnce() {
        var model = new ScriptedModel(List.of(List.of(text("Hel"), text("lo"))));
        model.usage = ChatUsage.builder().inputTokens(7).outputTokens(3).build();

        var session = run(model, List.of());

        assertEquals(1, session.usages.size(), "usage of the round: " + session.usages);
        assertEquals(7L, session.usages.get(0).input());
    }

    @Test
    void aStructuredAnswerReachesTheSessionOnce() {
        var model = new ScriptedModel(List.of(List.of(toolUse("call-1", "generate_response",
                "{\"response\":{\"answer\":true,\"confidence\":0.9}}",
                Map.of("response", Map.of("answer", true, "confidence", 0.9))))));
        var agent = ReActAgent.builder().name("base").sysPrompt("sys").model(model).build();
        var context = new AgentExecutionContext(
                "Is it raining?", "sys", null, null, "s", null, null,
                List.of(convert(new AtomicInteger())), null, null, List.of(), Map.of(),
                List.of(), Reply.class, null)
                .withMetadata(Map.of(NativeStructuredOutput.APPLY_METADATA_KEY, Boolean.TRUE));
        var session = new RecordingSession();

        new AgentScopeVisionWireShapeTest.TestableAgentScopeRuntime(agent).execute(context, session);

        var text = String.join("", session.sends);
        assertTrue(text.contains("\"confidence\""), "the structured answer reached the session: " + session.sends);
        assertEquals(text.indexOf("\"confidence\""), text.lastIndexOf("\"confidence\""),
                "the structured answer reached the session once: " + session.sends);
    }

    private static RecordingSession run(ScriptedModel model, List<ToolDefinition> tools) {
        var session = new RecordingSession();
        var agent = ReActAgent.builder().name("base").sysPrompt("sys").model(model).build();
        new AgentScopeVisionWireShapeTest.TestableAgentScopeRuntime(agent).execute(context(tools), session);
        return session;
    }

    private static ToolDefinition convert(AtomicInteger ran) {
        return ToolDefinition.builder("convert", "Convert Celsius to Fahrenheit")
                .parameter("celsius", "Degrees Celsius", "number")
                .executor(args -> {
                    ran.incrementAndGet();
                    return "41";
                })
                .build();
    }

    record Reply(Boolean answer, Double confidence) {
    }

    static ContentBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    static ContentBlock toolUse(String id, String name, String content, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).content(content).build();
    }

    static AgentExecutionContext context(List<ToolDefinition> tools) {
        return new AgentExecutionContext(
                "hi", "sys", "model", null, "sess", null, null,
                tools, null, null, null, Map.of(), List.of(), null, null);
    }

    /** A model that streams one scripted list of chunks per call. */
    static final class ScriptedModel implements Model {
        private final List<List<ContentBlock>> script;
        private final AtomicInteger calls = new AtomicInteger();
        volatile String toolResultText = "";
        volatile ChatUsage usage;

        ScriptedModel(List<List<ContentBlock>> script) {
            this.script = script;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                var n = calls.getAndIncrement();
                for (var m : messages) {
                    for (var block : m.getContent()) {
                        if (block instanceof ToolResultBlock result && !result.getOutput().isEmpty()
                                && result.getOutput().get(0) instanceof TextBlock t) {
                            toolResultText = t.getText();
                        }
                    }
                }
                var chunks = n < script.size() ? script.get(n) : List.<ContentBlock>of(text("done"));
                var responses = new ArrayList<ChatResponse>();
                for (var i = 0; i < chunks.size(); i++) {
                    var response = ChatResponse.builder().id("r" + n).content(List.of(chunks.get(i)));
                    if (i == chunks.size() - 1 && usage != null) {
                        response.usage(usage);
                    }
                    responses.add(response.build());
                }
                return Flux.fromIterable(responses);
            });
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    /** Records every text send and every structured frame. */
    static final class RecordingSession implements StreamingSession {
        final List<String> sends = new CopyOnWriteArrayList<>();
        final List<AiEvent> events = new CopyOnWriteArrayList<>();
        final List<TokenUsage> usages = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        List<AiEvent> toolEvents() {
            return events.stream()
                    .filter(e -> e instanceof AiEvent.ToolStart || e instanceof AiEvent.ToolResult
                            || e instanceof AiEvent.ToolError)
                    .toList();
        }

        List<String> toolFrames() {
            return toolEvents().stream().map(e -> switch (e) {
                case AiEvent.ToolStart start -> "ToolStart:" + start.toolName();
                case AiEvent.ToolResult result -> "ToolResult:" + result.toolName();
                case AiEvent.ToolError error -> "ToolError:" + error.toolName();
                default -> e.toString();
            }).toList();
        }

        @Override
        public void usage(TokenUsage usage) {
            usages.add(usage);
        }

        @Override
        public String sessionId() {
            return "rec";
        }

        @Override
        public void send(String text) {
            sends.add(text);
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
            if (event instanceof AiEvent.TextDelta delta) {
                sends.add(delta.text());
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            closed = true;
        }

        @Override
        public void complete(String summary) {
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
