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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the demo-mode tool router executes the sample's REAL
 * {@code @AiTool} methods through the framework's approval-aware execution
 * seam — not a keyword-driven simulation: real {@link AiEvent.ToolStart} /
 * {@link AiEvent.ToolResult} frames are emitted, real tool bodies run, and
 * the {@code @RequiresApproval} gate on {@code reset_city_data} withholds
 * execution until approved (failing closed when no strategy is wired).
 */
class DemoToolRouterTest {

    /** Records every emitted {@link AiEvent} so frames can be asserted. */
    private static final class CapturingSession implements StreamingSession {
        private final List<AiEvent> events = new CopyOnWriteArrayList<>();

        @Override public String sessionId() {
            return "demo-router-test";
        }

        @Override public void send(String text) {
        }

        @Override public void sendMetadata(String key, Object value) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete() {
        }

        @Override public void complete(String summary) {
        }

        @Override public void error(Throwable t) {
        }

        @Override public boolean isClosed() {
            return false;
        }

        @Override public void emit(AiEvent event) {
            events.add(event);
        }

        <T extends AiEvent> T firstEvent(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast)
                    .findFirst().orElse(null);
        }
    }

    /** Resolves every approval request to a fixed, caller-chosen outcome. */
    private record FixedOutcomeStrategy(ApprovalOutcome outcome) implements ApprovalStrategy {
        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            return outcome;
        }
    }

    // ── Keyword detection (the sample's demo-mode routing table) ──

    @Test
    void detectsTheFiveRoutedTools() {
        assertEquals("reset_city_data", DemoToolRouter.detectTool("Please reset city data for London"));
        assertEquals("get_city_time", DemoToolRouter.detectTool("What time is it in Tokyo?"));
        assertEquals("get_current_time", DemoToolRouter.detectTool("What time is it?"));
        assertEquals("get_weather", DemoToolRouter.detectTool("What is the weather in Paris?"));
        assertEquals("convert_temperature", DemoToolRouter.detectTool("Convert 100F to Celsius"));
        assertNull(DemoToolRouter.detectTool("Hello!"),
                "a greeting must not route to any tool");
    }

    @Test
    void buildsArgumentsMatchingTheRealToolSchemas() {
        assertEquals(Map.of("city", "tokyo"),
                DemoToolRouter.buildToolArgs("get_city_time", "What time is it in Tokyo?"));
        assertEquals(Map.of("city", "paris"),
                DemoToolRouter.buildToolArgs("get_weather", "weather in Paris"));
        assertEquals(Map.of("city", "london"),
                DemoToolRouter.buildToolArgs("reset_city_data", "reset city data for London"));
        // convert_temperature takes value (double) + from_unit — the REAL
        // @Param names, so ToolArgumentValidator admits the call.
        assertEquals(Map.of("value", 100.0, "from_unit", "F"),
                DemoToolRouter.buildToolArgs("convert_temperature", "Convert 100F to Celsius"));
        assertEquals(Map.of("value", 25.0, "from_unit", "C"),
                DemoToolRouter.buildToolArgs("convert_temperature", "convert 25 celsius"));
    }

    // ── Real execution with real wire events ──

    @Test
    void weatherQueryExecutesTheRealToolAndEmitsToolFrames() {
        var session = new CapturingSession();

        assertTrue(DemoToolRouter.route("What is the weather in Paris?", session),
                "a weather keyword must route to get_weather");

        var start = session.firstEvent(AiEvent.ToolStart.class);
        assertEquals("get_weather", start.toolName());
        assertEquals(Map.of("city", "paris"), start.arguments());

        var result = session.firstEvent(AiEvent.ToolResult.class);
        assertEquals("get_weather", result.toolName());
        // The REAL AssistantTools.getWeather body ran — its canned Paris
        // report, not a router-fabricated {"status":"success"} placeholder.
        assertTrue(String.valueOf(result.result()).contains("Paris: Partly cloudy"),
                "the real tool body must produce the result, got: " + result.result());
    }

    @Test
    void unmatchedPromptRoutesNothing() {
        var session = new CapturingSession();
        assertFalse(DemoToolRouter.route("Hello!", session));
        assertNull(session.firstEvent(AiEvent.ToolStart.class),
                "no tool frame may be emitted when no keyword matches");
    }

    // ── The @RequiresApproval gate on reset_city_data, driven for real ──

    @Test
    void resetCityDataFailsClosedWhenNoApprovalStrategyCanBeWired() {
        // A bare test session cannot be unwrapped to an AiStreamingSession, so
        // the router derives no ApprovalStrategy and executeWithApproval must
        // fail closed — the destructive tool body never runs.
        var session = new CapturingSession();

        assertTrue(DemoToolRouter.route("Please reset city data for London", session));

        var result = session.firstEvent(AiEvent.ToolResult.class);
        assertTrue(String.valueOf(result.result()).contains("cancelled"),
                "un-wired approval must fail closed, got: " + result.result());
    }

    @Test
    void resetCityDataRunsOnlyAfterApproval() {
        var registry = new DefaultToolRegistry();
        registry.register(new AssistantTools());
        var tool = registry.getTool("reset_city_data").orElseThrow();
        assertTrue(tool.requiresApproval(),
                "reset_city_data must carry @RequiresApproval");
        var args = DemoToolRouter.buildToolArgs("reset_city_data", "reset city data for London");

        var denied = new CapturingSession();
        var deniedResult = ToolExecutionHelper.executeWithApproval(
                "reset_city_data", tool, args, denied,
                new FixedOutcomeStrategy(ApprovalStrategy.ApprovalOutcome.DENIED));
        assertTrue(deniedResult.contains("cancelled"),
                "denied approval must cancel, got: " + deniedResult);

        var approved = new CapturingSession();
        var approvedResult = ToolExecutionHelper.executeWithApproval(
                "reset_city_data", tool, args, approved,
                new FixedOutcomeStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED));
        assertTrue(approvedResult.contains("has been reset successfully"),
                "approved call must run the real tool body, got: " + approvedResult);
    }
}
