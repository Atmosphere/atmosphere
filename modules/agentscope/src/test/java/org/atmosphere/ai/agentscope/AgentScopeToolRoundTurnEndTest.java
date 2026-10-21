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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.ApprovalTimedOutException;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more.
 * AgentScope's {@link ReActAgent} owns the loop; the test runs the per-request
 * agent the runtime builds over a model that asks for a tool on its first call.
 */
class AgentScopeToolRoundTurnEndTest {

    private static AgentExecutionContext context(ToolDefinition tool) {
        return new AgentExecutionContext(
                "add", "sys", "model", null, "sess", null, null,
                List.of(tool), null, null, null, Map.of(), List.of(), null, null);
    }

    private static List<Msg> userMessage() {
        return List.of(Msg.builder().role(MsgRole.USER).textContent("add").build());
    }

    @Test
    void toolRoundThatEndedTheTurnMakesNoFollowUpModelCall() {
        var model = new ToolThenTextModel();
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> {
                    session.error(new ApprovalTimedOutException(
                            "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"));
                    return "{\"status\":\"timeout\"}";
                })
                .build();
        var agent = AgentScopeAgentRuntime.rebuildAgent(
                ReActAgent.builder().name("base").sysPrompt("sys").model(model).build(),
                context(tool), session, null);

        agent.call(userMessage()).block(Duration.ofSeconds(10));

        assertTrue(session.isClosed(), "the tool round ended the turn");
        assertEquals(1, model.calls.get(), "no follow-up model call once the turn ended");
    }

    @Test
    void toolRoundThatLeavesTheTurnLiveStillGetsTheModelsAnswer() {
        var model = new ToolThenTextModel();
        var ran = new AtomicInteger();
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> {
                    ran.incrementAndGet();
                    return "2";
                })
                .build();
        var agent = AgentScopeAgentRuntime.rebuildAgent(
                ReActAgent.builder().name("base").sysPrompt("sys").model(model).build(),
                context(tool), session, null);

        var reply = agent.call(userMessage()).block(Duration.ofSeconds(10));

        assertEquals(1, ran.get(), "the tool ran");
        assertEquals(2, model.calls.get(), "the model answers after the tool round");
        assertEquals("late answer", reply.getTextContent());
    }

    /** Asks for the {@code add} tool on its first call, answers with text after. */
    private static final class ToolThenTextModel implements Model {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                ContentBlock block = calls.incrementAndGet() == 1
                        ? ToolUseBlock.builder().id("call-1").name("add")
                                .input(Map.of("a", 1)).content("{\"a\":1}").build()
                        : TextBlock.builder().text("late answer").build();
                return Flux.just(ChatResponse.builder().id("r").content(List.of(block)).build());
            });
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }
    }
}
