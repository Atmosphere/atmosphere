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
package org.atmosphere.samples.springboot.langchain4jtools;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Simulates LLM streaming responses with tool calling for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * @param userMessage the user's prompt
     * @param session     the streaming session
     * @param room        the room path parameter (from the URL template)
     * @param clientId    the AtmosphereResource UUID
     */
    public static void stream(String userMessage, StreamingSession session,
                              String room, String clientId) {
        var response = generateResponse(userMessage, room, clientId);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — room: " + room + ", client: " + clientId);

            // Emit tool events so the frontend ToolActivity panel shows activity
            var toolName = detectTool(userMessage);
            if (toolName != null) {
                var toolArgs = buildToolArgs(toolName, userMessage);
                session.emit(new AiEvent.ToolStart(toolName, toolArgs));
                Thread.sleep(100);
                session.emit(new AiEvent.ToolResult(toolName, Map.of("status", "success")));
            }

            for (var word : words) {
                session.send(word);
                Thread.sleep(50);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage, String room, String clientId) {
        var lower = userMessage.toLowerCase();

        if (lower.contains("time") && containsCity(lower)) {
            var city = extractCity(lower);
            return simulateToolCall("cityTime", city);
        }
        if (lower.contains("time")) {
            return simulateToolCall("currentTime", null);
        }
        if (lower.contains("weather")) {
            var city = extractCity(lower);
            return simulateToolCall("weather", city != null ? city : "New York");
        }
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm a LangChain4j-powered assistant running through Atmosphere. "
                    + "I can tell you the current time, city times, and weather reports. "
                    + "Try asking: \"What's the weather in Paris?\" or \"What time is it in Tokyo?\" "
                    + "(Demo mode — set LLM_API_KEY for real tool calls)";
        }
        if (lower.contains("tool")) {
            return "I have three tools available: currentTime (returns the current date/time), "
                    + "cityTime (returns time in a specific city — New York, London, Paris, Tokyo, Sydney), "
                    + "and weather (returns a weather report for a city). "
                    + "These tools are registered via LangChain4j's @Tool annotation and discovered "
                    + "automatically by the AI Service.";
        }
        if (lower.contains("pii") || lower.contains("redact")) {
            return "The PII redaction filter is active! Try sending a message with an email "
                    + "like john.doe@example.com or a phone number like 555-123-4567. "
                    + "The filter will replace them with [REDACTED] before they reach your browser. "
                    + "My SSN is 123-45-6789 and my email is alice@company.org — "
                    + "you should see these redacted in the response.";
        }
        return "This is a demo response with PII redaction and cost metering active. "
                + "Try asking about the time or weather, or send a message containing "
                + "an email address to see PII redaction in action.";
    }

    private static String simulateToolCall(String toolName, String arg) {
        return switch (toolName) {
            case "currentTime" -> {
                var now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                yield "I called the currentTime tool. The current date and time is " + now + ". "
                        + "This tool call was simulated in demo mode — with a real LLM, the model "
                        + "would autonomously decide when to invoke tools.";
            }
            case "cityTime" -> {
                var zone = switch (arg) {
                    case "tokyo" -> "Asia/Tokyo";
                    case "london" -> "Europe/London";
                    case "paris" -> "Europe/Paris";
                    case "sydney" -> "Australia/Sydney";
                    default -> "America/New_York";
                };
                var time = LocalDateTime.now(ZoneId.of(zone))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                yield "I called the cityTime tool for " + arg + ". The current time there is " + time + ". "
                        + "In real mode, the LLM would decide to call this tool based on your question.";
            }
            case "weather" -> {
                var report = switch (arg) {
                    case "london" -> "Cloudy, 15°C / 59°F";
                    case "paris" -> "Partly cloudy, 20°C / 68°F";
                    case "tokyo" -> "Rainy, 22°C / 72°F";
                    case "sydney" -> "Clear, 28°C / 82°F";
                    default -> "Sunny, 25°C / 77°F";
                };
                yield "I called the weather tool for " + arg + ". The weather is: " + report + ". "
                        + "In real mode, the LLM would decide to call this tool based on your question.";
            }
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

    private static String detectTool(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("time") && containsCity(lower)) return "cityTime";
        if (lower.contains("time")) return "currentTime";
        if (lower.contains("weather")) return "weather";
        return null;
    }

    private static Map<String, Object> buildToolArgs(String toolName, String userMessage) {
        var lower = userMessage.toLowerCase();
        return switch (toolName) {
            case "cityTime" -> {
                var city = extractCity(lower);
                yield city != null ? Map.of("city", city) : Map.of();
            }
            case "weather" -> {
                var city = extractCity(lower);
                yield Map.of("city", (Object) (city != null ? city : "New York"));
            }
            default -> Map.of();
        };
    }
}
