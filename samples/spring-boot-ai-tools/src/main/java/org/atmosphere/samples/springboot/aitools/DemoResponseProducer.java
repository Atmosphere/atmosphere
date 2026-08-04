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

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Installs a tool-aware demo strategy so the bundled {@link DemoAgentRuntime}
 * streams this sample's canned tool-calling narratives instead of its generic
 * echo when the sample boots without an {@code LLM_API_KEY}. Runs at startup;
 * the framework then drives every demo-mode request through the standard
 * pipeline — guardrails, interceptors, memory, metrics, tape, and the dev
 * inspector all fire — like any real runtime.
 *
 * <p>The <em>text</em> below only narrates the tool call; the tool itself is
 * executed for real (with real {@code ToolStart}/{@code ToolResult} frames and
 * the real {@code @RequiresApproval} gate) by {@link DemoToolRouter} before
 * the {@code @Prompt} method streams the message through the pipeline.</p>
 */
@Component
public class DemoResponseProducer {

    @PostConstruct
    public void installToolAwareStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    private static String generateFor(AgentExecutionContext context) {
        return generateResponse(context.message() != null ? context.message() : "");
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();

        if (lower.contains("time") && containsCity(lower)) {
            var city = extractCity(lower);
            return simulateToolCall("get_city_time", city);
        }
        if (lower.contains("time")) {
            return simulateToolCall("get_current_time", null);
        }
        if (lower.contains("weather")) {
            var city = extractCity(lower);
            return simulateToolCall("get_weather", city != null ? city : "New York");
        }
        if (lower.contains("convert") && lower.contains("temperature")
                || lower.contains("celsius") || lower.contains("fahrenheit")) {
            return simulateToolCall("convert_temperature", "100 C");
        }
        if (lower.contains("reset")) {
            var city = extractCity(lower);
            return simulateToolCall("reset_city_data", city != null ? city : "unknown");
        }
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm an Atmosphere-powered assistant with framework-agnostic tools. "
                    + "I can tell you the time, weather, and convert temperatures. "
                    + "Try: \"What's the weather in Tokyo?\" or \"Convert 100°F to Celsius\" "
                    + "(Demo mode — set LLM_API_KEY for real tool calls)";
        }
        if (lower.contains("tool")) {
            return "I have four @AiTool-annotated tools: get_current_time, get_city_time, "
                    + "get_weather, and convert_temperature. Unlike LangChain4j's native @Tool, "
                    + "these use Atmosphere's @AiTool annotation and work with ANY backend — "
                    + "Spring AI, LangChain4j, or Google ADK. The tool bridge layer automatically "
                    + "converts them to the native format at runtime.";
        }
        return "This sample demonstrates Atmosphere's @AiTool pipeline. "
                + "Tools declared with @AiTool are framework-agnostic — they work with "
                + "Spring AI, LangChain4j, and Google ADK without code changes. "
                + "Try asking about the time, weather, or temperature conversion!";
    }

    private static String simulateToolCall(String toolName, String arg) {
        return switch (toolName) {
            case "get_current_time" -> {
                var now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                yield "[Tool: get_current_time] The current date and time is " + now + ". "
                        + "This was executed via Atmosphere's @AiTool pipeline.";
            }
            case "get_city_time" -> {
                var zone = switch (arg) {
                    case "tokyo" -> "Asia/Tokyo";
                    case "london" -> "Europe/London";
                    case "paris" -> "Europe/Paris";
                    case "sydney" -> "Australia/Sydney";
                    default -> "America/New_York";
                };
                var time = LocalDateTime.now(ZoneId.of(zone))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                yield "[Tool: get_city_time] The time in " + arg + " is " + time + ". "
                        + "This tool was registered with @AiTool and bridged to the active backend.";
            }
            case "get_weather" -> {
                var report = switch (arg) {
                    case "london" -> "Cloudy, 15°C / 59°F, 80% humidity";
                    case "paris" -> "Partly cloudy, 20°C / 68°F, 65% humidity";
                    case "tokyo" -> "Rainy, 22°C / 72°F, 90% humidity";
                    case "sydney" -> "Clear, 28°C / 82°F, 45% humidity";
                    default -> "Sunny, 25°C / 77°F, 55% humidity";
                };
                yield "[Tool: get_weather] Weather in " + arg + ": " + report + ". "
                        + "This tool was registered with @AiTool — portable across backends.";
            }
            case "convert_temperature" ->
                    "[Tool: convert_temperature] 100.0°C = 212.0°F. "
                        + "This tool was registered with @AiTool and runs on any backend.";
            case "reset_city_data" ->
                    "[Tool: reset_city_data] All cached data for " + arg + " has been reset. "
                        + "This tool uses @RequiresApproval — the user must approve before execution.";
            default -> "Unknown tool: " + toolName;
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
