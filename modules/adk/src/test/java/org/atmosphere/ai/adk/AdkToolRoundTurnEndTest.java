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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.atmosphere.ai.ApprovalTimedOutException;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more. ADK's
 * flow owns the loop; the test runs a real {@link InMemoryRunner} over a model
 * that asks for a tool on its first call.
 */
class AdkToolRoundTurnEndTest {

    @Test
    void toolRoundThatEndedTheTurnMakesNoFollowUpModelCall() {
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> {
                    session.error(new ApprovalTimedOutException(
                            "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"));
                    return "{\"status\":\"timeout\"}";
                })
                .build();
        var model = new ToolThenTextLlm();
        var agent = LlmAgent.builder()
                .name("assistant")
                .instruction("help")
                .model(model)
                .tools(AdkToolBridge.toAdkTools(List.of(tool), session, null, List.of(), null))
                .build();
        var runner = new InMemoryRunner(agent, "turn-end");
        var adkSession = runner.sessionService().createSession("turn-end", "user").blockingGet();

        runner.runAsync("user", adkSession.id(), Content.fromParts(Part.fromText("add")))
                .blockingSubscribe();

        assertTrue(session.isClosed(), "the tool round ended the turn");
        assertEquals(1, model.calls.get(), "no follow-up model call once the turn ended");
    }

    /** Asks for the {@code add} tool on its first call, answers with text after. */
    private static final class ToolThenTextLlm extends BaseLlm {

        private final AtomicInteger calls = new AtomicInteger();

        ToolThenTextLlm() {
            super("fake-model");
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            var part = calls.incrementAndGet() == 1
                    ? Part.fromFunctionCall("add", Map.of("a", 1))
                    : Part.fromText("late answer");
            return Flowable.just(LlmResponse.builder()
                    .content(Content.builder().role("model").parts(List.of(part)).build())
                    .build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException("live mode is not used here");
        }
    }
}
