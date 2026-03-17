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
package org.atmosphere.samples.springboot.adktools;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Simulates ADK event streams with tool calling for demo/testing purposes.
 * Ported from the official adk-java/tutorials/city-time-weather example.
 */
public final class DemoEventProducer {

    private DemoEventProducer() {
    }

    /**
     * @param userMessage the user's prompt
     * @param clientId    the AtmosphereResource UUID for budget tracking
     */
    public static Flowable<Event> stream(String userMessage, String clientId) {
        var response = generateResponse(userMessage, clientId);
        var words = response.split("(?<=\\s)");

        return Flowable.fromArray(words)
                .zipWith(Flowable.interval(50, TimeUnit.MILLISECONDS), (word, tick) -> word)
                .map(DemoEventProducer::partialEvent)
                .concatWith(Flowable.just(turnCompleteEvent()));
    }

    private static String generateResponse(String userMessage, String clientId) {
        var lower = userMessage.toLowerCase();

        if (lower.contains("time") && containsCity(lower)) {
            var city = extractCity(lower);
            return simulateToolCall("getCurrentTime", city);
        }
        if (lower.contains("time")) {
            return simulateToolCall("getCurrentTime", "new york");
        }
        if (lower.contains("weather")) {
            var city = extractCity(lower);
            return simulateToolCall("getWeather", city != null ? city : "new york");
        }
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm a Google ADK agent running on Atmosphere Framework. "
                    + "I have tools for checking city times and weather reports. "
                    + "Try: \"What time is it in Tokyo?\" or \"What's the weather in London?\" "
                    + "(Demo mode with streaming text budget tracking active)";
        }
        if (lower.contains("budget") || lower.contains("streaming text")) {
            return "Streaming text budget management is active! Each user gets 10,000 streaming texts. "
                    + "When usage reaches 80%, the system gracefully degrades to a cheaper model "
                    + "(gemini-2.0-flash-lite). When the budget is exhausted, new requests are blocked "
                    + "until the budget resets. This prevents runaway costs in production.";
        }
        if (lower.contains("cache") || lower.contains("caching")) {
            return "Response caching is enabled via AiResponseCacheInspector. "
                    + "Completed AI responses are cached and can be replayed to clients that "
                    + "reconnect after a disconnect. Progress messages (like 'Thinking...') "
                    + "are ephemeral and not cached. This reduces API calls and improves UX.";
        }
        return "This is a demo ADK agent with tool calling, streaming text budgets, and response caching. "
                + "Try asking about the time or weather in a city, or ask about 'budget' or 'cache'.";
    }

    private static String simulateToolCall(String toolName, String city) {
        return switch (toolName) {
            case "getCurrentTime" -> {
                var zone = switch (city) {
                    case "tokyo" -> "Asia/Tokyo";
                    case "london" -> "Europe/London";
                    case "paris" -> "Europe/Paris";
                    case "sydney" -> "Australia/Sydney";
                    default -> "America/New_York";
                };
                var time = LocalDateTime.now(ZoneId.of(zone))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                yield "[Tool: getCurrentTime(" + city + ")] The current time in "
                        + city + " is " + time + ". "
                        + "This tool call was simulated — with real Gemini credentials, "
                        + "the ADK agent would autonomously invoke FunctionTool declarations.";
            }
            case "getWeather" -> {
                var weather = switch (city) {
                    case "london" -> "Cloudy, 15°C";
                    case "paris" -> "Partly cloudy, 20°C";
                    case "tokyo" -> "Rainy, 22°C";
                    case "sydney" -> "Clear, 28°C";
                    default -> "Sunny, 25°C";
                };
                yield "[Tool: getWeather(" + city + ")] Weather in "
                        + city + ": " + weather + ". "
                        + "In production, this would call a real weather API through "
                        + "ADK's FunctionTool mechanism.";
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

    private static Event partialEvent(String text) {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("demo-invocation")
                .author("model")
                .actions(EventActions.builder().build())
                .partial(Optional.of(true))
                .content(Optional.of(Content.fromParts(Part.fromText(text))))
                .build();
    }

    private static Event turnCompleteEvent() {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("demo-invocation")
                .author("model")
                .actions(EventActions.builder().build())
                .turnComplete(Optional.of(true))
                .build();
    }

    /**
     * Detect which tool the user prompt would invoke, or null if none.
     */
    static String detectTool(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("time") && containsCity(lower)) return "getCurrentTime";
        if (lower.contains("time")) return "getCurrentTime";
        if (lower.contains("weather")) return "getWeather";
        return null;
    }

    /**
     * Build tool arguments for the detected tool call.
     */
    static Map<String, Object> buildToolArgs(String toolName, String userMessage) {
        var lower = userMessage.toLowerCase();
        return switch (toolName) {
            case "getCurrentTime" -> {
                var city = extractCity(lower);
                yield city != null ? Map.of("city", city) : Map.of("city", "new york");
            }
            case "getWeather" -> {
                var city = extractCity(lower);
                yield Map.of("city", (Object) (city != null ? city : "new york"));
            }
            default -> Map.of();
        };
    }
}
