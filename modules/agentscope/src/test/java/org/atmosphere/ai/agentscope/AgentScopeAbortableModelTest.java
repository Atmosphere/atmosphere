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
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamIdleWatchdog;
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The model of an agent Atmosphere rebuilds for a turn with tools: a model
 * that streams one chunk and then nothing ends the turn once in both dispatch
 * modes, and the inner model {@code Flux} is cancelled (the dispose of the
 * agent's event stream alone never reached it; for AgentScope's HTTP models the
 * cancel closes the response body). The acting hook pauses the turn's clock
 * from a tool's start to its end.
 */
class AgentScopeAbortableModelTest {

    /** A model that streams one chunk, then nothing, and counts the cancels of its stream. */
    private static final class SilentModel implements Model {
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("tick").build()))
                            .build())
                    .concatWith(Flux.never())
                    .doOnCancel(cancels::incrementAndGet);
        }

        @Override
        public String getModelName() {
            return "silent";
        }
    }

    @Test
    void aSilentModelOfARebuiltAgentEndsTheTurnOnceAndIsCancelled() throws Exception {
        var models = new java.util.concurrent.CopyOnWriteArrayList<SilentModel>();
        var tool = new ToolDefinition("noop", "no-op test tool", List.of(), "string", args -> "ok", null, 0);
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var model = new SilentModel();
            models.add(model);
            var agent = ReActAgent.builder().name("base").sysPrompt("you are a fixture").model(model).build();
            return new AgentScopeRuntimeContractTest.TestableAgentScopeRuntime(agent);
        }, new AgentExecutionContext("Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", null, List.of(tool), null, null, List.of(), Map.of(),
                List.of(), null, null), "the inner model Flux's cancel",
                () -> models.stream().mapToInt(m -> m.cancels.get() > 0 ? 1 : 0).sum());
    }

    @Test
    void theActingHookPausesTheClockFromAToolsStartToItsEnd() throws Exception {
        try (var watchdog = StreamIdleWatchdog.open(Duration.ofMinutes(1), new CollectingSession(), "agentscope")) {
            var model = new AbortableModel(new SilentModel(), Optional.of(watchdog));
            var hook = model.actingPause();
            var toolUse = ToolUseBlock.builder().id("call-1").name("noop").input(Map.of()).build();
            var agent = ReActAgent.builder().name("base").sysPrompt("you are a fixture").model(model).build();
            var toolkit = new io.agentscope.core.tool.Toolkit();
            hook.onEvent(new PreActingEvent(agent, toolkit, toolUse)).block();
            assertEquals(1, model.pausedTools());
            hook.onEvent(new PostActingEvent(agent, toolkit, toolUse, null)).block();
            assertEquals(0, model.pausedTools());

            hook.onEvent(new PreActingEvent(agent, toolkit, toolUse)).block();
            model.ended();
            assertEquals(0, model.pausedTools(), "the turn's end closes a pause whose end was never reported");
        }
    }
}
