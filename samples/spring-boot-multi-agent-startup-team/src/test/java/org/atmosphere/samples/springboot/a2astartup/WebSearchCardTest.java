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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.websearch.WebSearchQuery;
import org.atmosphere.ai.websearch.WebSearchSupport;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code web_search} card the CEO coordinator emits must say whether the
 * search ran: with no engine configured the research task fails and the card
 * ends with {@link AiEvent.ToolError} (the Console's "Failed"), while a search
 * that ran ends with {@link AiEvent.ToolResult}. Either way the text the next
 * agents and the model read is the research text, unchanged.
 */
class WebSearchCardTest {

    @Test
    void researchAgentFailsItsTaskWhenNoEngineIsConfigured() {
        var expected = WebSearchSupport.shared().search(WebSearchQuery.of("ai fitness apps", 3));
        assertFalse(expected.available(),
                "precondition: the test JVM configures no web search engine");

        var task = new TaskContext("task-1", "ctx-1");
        new ResearchAgent().webSearch(task, "ai fitness apps", "3");

        assertEquals(TaskState.FAILED, task.state(),
                "a search that did not run must fail the research task, not complete it");
        assertEquals(expected.toModelText(), task.statusMessage(),
                "the failure carries the same fail-closed text the coordinator read before");
        assertTrue(task.artifacts().isEmpty(),
                "a failed search adds no artifact (a stream would repeat the error text)");
    }

    @Test
    void searchThatDidNotRunEndsTheCardWithToolError() {
        var notConfigured = "Web search is not configured on this server.";
        var events = runPrompt(AgentResult.failure("research-agent", "web_search",
                notConfigured, Duration.ofMillis(5)));

        var card = webSearchCard(events);
        var error = assertInstanceOf(AiEvent.ToolError.class, card,
                "a search that did not run must end its card as failed, got " + card);
        assertEquals(notConfigured, error.error());
    }

    @Test
    void searchThatRanEndsTheCardWithToolResult() {
        var hits = "Web search results for: \"ai fitness apps\"\n\n[1] Market report";
        var events = runPrompt(new AgentResult("research-agent", "web_search",
                hits, Map.of(), Duration.ofMillis(5), true));

        var card = webSearchCard(events);
        var result = assertInstanceOf(AiEvent.ToolResult.class, card,
                "a search that ran must end its card with its result, got " + card);
        assertEquals(hits, result.result());
    }

    /** Drives the real {@code @Prompt} with a stub fleet and records every emitted event. */
    private static List<AiEvent> runPrompt(AgentResult research) {
        var fleet = mock(AgentFleet.class);
        var researchProxy = mock(AgentProxy.class);
        var other = new AgentResult("other-agent", "skill", "text", Map.of(),
                Duration.ofMillis(1), true);
        when(fleet.withActivityListener(any())).thenReturn(fleet);
        when(fleet.withParentRun(any())).thenReturn(fleet);
        when(fleet.agent("research-agent")).thenReturn(researchProxy);
        when(researchProxy.call(eq("web_search"), anyMap())).thenReturn(research);
        when(fleet.call(anyString(), anyString(), anyMap())).thenReturn(mock(AgentCall.class));
        when(fleet.parallel(any(AgentCall[].class))).thenReturn(
                Map.of("strategy-agent", other, "finance-agent", other));
        when(fleet.refineUntil(any(AgentCall.class))).thenReturn(other);
        when(fleet.route(any(), any())).thenReturn(other);

        var session = mock(StreamingSession.class);
        new CeoCoordinator().onPrompt("ai fitness apps", fleet, session, null);

        var captor = ArgumentCaptor.forClass(AiEvent.class);
        verify(session, atLeastOnce()).emit(captor.capture());
        return captor.getAllValues();
    }

    /** The one terminal event of the {@code web_search} card. */
    private static AiEvent webSearchCard(List<AiEvent> events) {
        var terminal = events.stream()
                .filter(e -> (e instanceof AiEvent.ToolResult r && "web_search".equals(r.toolName()))
                        || (e instanceof AiEvent.ToolError err && "web_search".equals(err.toolName())))
                .toList();
        assertEquals(1, terminal.size(), "exactly one terminal web_search event, got " + events);
        return terminal.get(0);
    }
}
