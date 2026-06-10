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

import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.ai.tool.ToolSelection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dynamic tool pre-filtering (P2.16) — the shared helper + the AiPipeline seam. */
class AiToolSelectionTest {

    private ToolRegistry catalog() {
        var registry = new DefaultToolRegistry();
        registry.register(tool("weather_lookup", "get the current weather forecast for a city"));
        registry.register(tool("stock_price", "get the latest stock market share price"));
        registry.register(tool("send_email", "send an email message to a recipient"));
        registry.register(tool("calendar_add", "add a calendar appointment event"));
        return registry;
    }

    private static ToolDefinition tool(String name, String desc) {
        return ToolDefinition.builder(name, desc).executor(a -> "ok").build();
    }

    private static List<String> names(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
    }

    @Test
    void selectsTheRelevantToolsForAQuery() {
        var selected = ToolSelection.select(catalog(), "what is the weather forecast today", 2);
        assertEquals(2, selected.size());
        assertTrue(names(selected).contains("weather_lookup"),
                "the weather query must keep the weather tool: " + names(selected));
    }

    @Test
    void zeroCapInjectsEveryTool() {
        assertEquals(4, ToolSelection.select(catalog(), "weather", 0).size());
    }

    @Test
    void underCapReturnsAllUnchanged() {
        assertEquals(4, ToolSelection.select(catalog(), "weather", 10).size());
    }

    @Test
    void noLexicalMatchFallsBackToFirstNNotEmpty() {
        var selected = ToolSelection.select(catalog(), "zzz qqq xyzzy", 2);
        assertEquals(2, selected.size(),
                "a query with no token overlap must fall back to first-N, not strip all tools");
    }

    @Test
    void pipelineCapsToolsHandedToTheRuntime() {
        var captured = new AtomicReference<AgentExecutionContext>();
        AgentRuntime runtime = new BareRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                captured.set(context);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, "sys", "m", null, catalog(), List.of(), List.of(), null);
        pipeline.setMaxToolsPerRequest(2);

        pipeline.execute("c1", "what is the weather forecast", new CollectingSession("sel"));

        assertEquals(2, captured.get().tools().size(),
                "the pipeline must cap injected tools to maxToolsPerRequest");
        assertTrue(captured.get().tools().stream().anyMatch(t -> t.name().equals("weather_lookup")));
    }

    private abstract static class BareRuntime implements AgentRuntime {
        @Override public String name() {
            return "bare";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public int priority() {
            return 0;
        }

        @Override public void configure(AiConfig.LlmSettings settings) {
            // no-op
        }
    }
}
