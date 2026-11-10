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
package org.atmosphere.ai;

import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageEntry;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A turn's lineage entry names the agent that served it and every tool call
 * the turn made, whichever path dispatched it (Mode Parity, Invariant #7): the
 * web path ({@link AiStreamingSession}, an {@code @Agent} / {@code @Coordinator}
 * reached over its WebSocket) and the pipeline path ({@link AiPipeline}, the
 * same agent reached over A2A, AG-UI or a channel).
 */
class LineageAttributionParityTest {

    @AfterEach
    void resetRecorder() {
        LineageRecorderHolder.reset();
    }

    @Test
    void bothPathsNameTheServedAgentOnTheLineageEntry() {
        var recorder = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(recorder);
        var runtime = runtime(StreamingSession::complete);

        var pipeline = new AiPipeline(runtime, "sys", "m", null, null,
                List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setLineageAgentId("ceo");
        pipeline.execute("c1", "hello", new CollectingSession("pipeline-1"));
        var pipelineEntry = onlyEntry(recorder);

        recorder.clear();
        var session = webSession(runtime, null);
        session.setLineageAgentId("ceo");
        session.stream("hello");
        var webEntry = onlyEntry(recorder);

        assertEquals("ceo", pipelineEntry.agentId());
        assertEquals(pipelineEntry.agentId(), webEntry.agentId());
    }

    @Test
    void anAgentIdOnTheRequestWinsOverTheServedAgentsName() {
        var recorder = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(recorder);
        var session = webSession(runtime(StreamingSession::complete), "billing-subagent");
        session.setLineageAgentId("ceo");

        session.stream("hello");

        assertEquals("billing-subagent", onlyEntry(recorder).agentId());
    }

    @Test
    void toolEventsThePromptMethodEmitsOnTheSessionAreRecordedOnce() {
        var recorder = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(recorder);
        var session = webSession(runtime(s -> {
            // A tool the model called, emitted through the composed chain.
            s.emit(new AiEvent.ToolStart("lookup", Map.of()));
            s.emit(new AiEvent.ToolResult("lookup", "ok"));
            s.complete();
        }), null);

        // What a @Coordinator's @Prompt method does before its synthesis call:
        // report each fleet dispatch as a tool card on the session it was given.
        session.emit(new AiEvent.ToolStart("web_search", Map.of("query", "q")));
        session.emit(new AiEvent.ToolResult("web_search", "results"));
        session.emit(new AiEvent.ToolStart("financial_model", Map.of()));
        session.emit(new AiEvent.ToolError("financial_model", "agent unavailable"));
        session.stream("write the briefing");

        assertEquals(List.of("web_search", "financial_model", "lookup"),
                onlyEntry(recorder).toolCalls().stream().map(LineageEntry.ToolCall::name).toList());
    }

    private static LineageEntry onlyEntry(InMemoryLineageRecorder recorder) {
        var entries = recorder.recent(10);
        assertEquals(1, entries.size(), "one turn records one lineage entry");
        return entries.get(0);
    }

    private static AiStreamingSession webSession(AgentRuntime runtime, String requestAgentId) {
        var request = AtmosphereRequestImpl.newInstance();
        if (requestAgentId != null) {
            request.setAttribute("ai.agentId", requestAgentId);
        }
        var resource = (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(), new Class<?>[]{AtmosphereResource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequest" -> request;
                    case "uuid" -> "lineage-parity-uuid";
                    case "toString" -> "lineage-parity-resource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE
                            : method.getReturnType().isPrimitive() && method.getReturnType() != void.class
                                    ? 0 : null;
                });
        return new AiStreamingSession(new CollectingSession("web-1"), runtime, "sys", "m",
                List.of(), resource);
    }

    private static AgentRuntime runtime(Consumer<StreamingSession> driver) {
        return new AgentRuntime() {
            @Override public String name() { return "lineage-stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING);
            }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                driver.accept(session);
            }
        };
    }
}
