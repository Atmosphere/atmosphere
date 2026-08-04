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

import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Demo-mode tool router: when no {@code LLM_API_KEY} is configured there is no
 * model to pick tools, so this router keyword-matches the prompt and invokes
 * the matching {@link AssistantTools} tool <em>for real</em> through
 * {@link ToolExecutionHelper#executeWithApproval}, the same choke point every
 * runtime bridge uses. That means real {@code ToolStart}/{@code ToolResult}
 * frames on the wire, real argument validation, and — for
 * {@code @RequiresApproval} tools like {@code reset_city_data} — the real
 * approval gate: a generated {@code apr_*} id, an {@code approval-required}
 * frame, and a parked virtual thread until the client answers with
 * {@code /__approval/<id>/approve} or {@code /__approval/<id>/deny} (routed by
 * the endpoint handler to the session's {@code ApprovalRegistry}).
 *
 * <p>The registry is built from the sample's own {@link AssistantTools} class,
 * so every {@code ToolDefinition} — including the {@code @RequiresApproval}
 * message and timeout — comes from the genuine annotations, not a stand-in.</p>
 */
public final class DemoToolRouter {

    private static final Logger logger = LoggerFactory.getLogger(DemoToolRouter.class);

    /** Matches the first number in the prompt for {@code convert_temperature}. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final ToolRegistry REGISTRY = buildRegistry();

    private DemoToolRouter() {
    }

    private static ToolRegistry buildRegistry() {
        var registry = new DefaultToolRegistry();
        registry.register(new AssistantTools());
        return registry;
    }

    /**
     * Keyword-match the prompt and, on a hit, execute the real tool through
     * the framework's approval-aware execution seam, emitting the same wire
     * events a model-driven tool call would.
     *
     * @param message the user prompt
     * @param session the live streaming session for this prompt
     * @return {@code true} if a tool was matched and executed (or gated)
     */
    public static boolean route(String message, StreamingSession session) {
        var toolName = detectTool(message);
        if (toolName == null) {
            return false;
        }
        var tool = REGISTRY.getTool(toolName).orElse(null);
        if (tool == null) {
            return false;
        }
        var args = buildToolArgs(toolName, message);
        // The approval strategy must park on the SESSION's registry — that is
        // the registry the endpoint handler routes /__approval/<id>/... client
        // responses to. When the session cannot be unwrapped (ad-hoc tests),
        // executeWithApproval fails closed for @RequiresApproval tools.
        var strategy = AiStreamingSession.unwrap(session)
                .map(ai -> ApprovalStrategy.virtualThread(ai.approvalRegistry()))
                .orElse(null);
        var result = ToolExecutionHelper.executeWithApproval(
                toolName, tool, args, session, strategy);
        logger.info("Demo tool router executed {} -> {}", toolName, result);
        return true;
    }

    static String detectTool(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("reset")) return "reset_city_data";
        if (lower.contains("time") && containsCity(lower)) return "get_city_time";
        if (lower.contains("time")) return "get_current_time";
        if (lower.contains("weather")) return "get_weather";
        if (lower.contains("convert") || lower.contains("celsius") || lower.contains("fahrenheit"))
            return "convert_temperature";
        return null;
    }

    static Map<String, Object> buildToolArgs(String toolName, String userMessage) {
        var lower = userMessage.toLowerCase();
        return switch (toolName) {
            case "get_city_time" -> {
                var city = extractCity(lower);
                yield Map.of("city", (Object) (city != null ? city : "New York"));
            }
            case "get_weather" -> {
                var city = extractCity(lower);
                yield Map.of("city", (Object) (city != null ? city : "New York"));
            }
            case "convert_temperature" -> {
                var matcher = NUMBER.matcher(lower);
                var value = matcher.find() ? Double.parseDouble(matcher.group()) : 100.0;
                // "Convert 100F to Celsius" converts FROM Fahrenheit; default
                // to Celsius otherwise, matching the sample's canned narrative.
                var fromUnit = lower.contains("fahrenheit to") || lower.matches(".*\\d\\s*°?f\\b.*")
                        ? "F" : "C";
                yield Map.of("value", (Object) value, "from_unit", fromUnit);
            }
            case "reset_city_data" -> {
                var city = extractCity(lower);
                yield Map.of("city", (Object) (city != null ? city : "unknown"));
            }
            default -> Map.of();
        };
    }

    private static boolean containsCity(String text) {
        return text.contains("new york") || text.contains("london")
                || text.contains("paris") || text.contains("tokyo") || text.contains("sydney");
    }

    private static String extractCity(String text) {
        if (text.contains("new york")) return "new york";
        if (text.contains("london")) return "london";
        if (text.contains("paris")) return "paris";
        if (text.contains("tokyo")) return "tokyo";
        if (text.contains("sydney")) return "sydney";
        return null;
    }
}
