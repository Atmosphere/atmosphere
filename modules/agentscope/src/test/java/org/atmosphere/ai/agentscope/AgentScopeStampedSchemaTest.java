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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.NativeStructuredOutput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mode Parity (Correctness Invariant #7) for provider-native structured output:
 * the schema the caller stamps goes to AgentScope through its schema overload,
 * as it does on every other native runtime, instead of one AgentScope derives
 * from the response class. A decision question's closed value set is narrower
 * than the generic reply record it is parsed into.
 */
class AgentScopeStampedSchemaTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"answer\":{\"type\":\"boolean\"},\"confidence\":{\"type\":\"number\"}},"
            + "\"required\":[\"answer\",\"confidence\"],\"additionalProperties\":false}";

    @Test
    void stampedSchemaGoesThroughTheSchemaOverload() {
        var agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class), any(JsonNode.class))).thenReturn(Flux.empty());
        when(agent.stream(anyList(), any(StreamOptions.class), any(Class.class))).thenReturn(Flux.empty());

        var context = NativeStructuredOutput.withApply(context(), SCHEMA);
        new AgentScopeVisionWireShapeTest.TestableAgentScopeRuntime(agent).execute(context, new CollectingSession());

        var captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(agent).stream(anyList(), any(StreamOptions.class), captor.capture());
        assertEquals("boolean", captor.getValue().path("properties").path("answer").path("type").asText());
        verify(agent, never()).stream(anyList(), any(StreamOptions.class), eq(Reply.class));
    }

    @Test
    void withoutAStampedSchemaTheClassOverloadIsUsed() {
        var agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class), any(Class.class))).thenReturn(Flux.empty());
        var context = context().withMetadata(Map.of(NativeStructuredOutput.APPLY_METADATA_KEY, Boolean.TRUE));
        new AgentScopeVisionWireShapeTest.TestableAgentScopeRuntime(agent).execute(context, new CollectingSession());
        verify(agent).stream(anyList(), any(StreamOptions.class), eq(Reply.class));
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "Is it raining?", "sys", null, null, "s", null, null,
                List.of(), null, null, List.of(), Map.of(),
                List.of(), Reply.class, null);
    }

    record Reply(Object answer, Double confidence) {
    }
}
