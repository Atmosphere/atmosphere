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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Framework-agnostic tool provider using Atmosphere's {@code @AiTool} annotation.
 *
 * <p>These tools are registered in the global {@code ToolRegistry} at startup
 * and automatically bridged to whichever AI backend is active:</p>
 * <ul>
 *   <li><b>Spring AI</b> → {@code ToolCallback} via {@code SpringAiToolBridge}</li>
 *   <li><b>LangChain4j</b> → {@code ToolSpecification} via {@code LangChain4jToolBridge}</li>
 *   <li><b>Google ADK</b> → {@code BaseTool} via {@code AdkToolBridge}</li>
 * </ul>
 *
 * <p>The key advantage: switch your AI backend without rewriting tool code.</p>
 */
public class AssistantTools {

    @AiTool(name = "get_current_time",
            description = "Returns the current date and time in the server's timezone")
    public String getCurrentTime() {
        return ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }

    @AiTool(name = "get_city_time",
            description = "Returns the current time in a specific city")
    public String getCityTime(
            @Param(value = "city", description = "City name (e.g., Tokyo, London, Paris, New York, Sydney)")
            String city) {
        var zone = switch (city.toLowerCase()) {
            case "tokyo" -> "Asia/Tokyo";
            case "london" -> "Europe/London";
            case "paris" -> "Europe/Paris";
            case "sydney" -> "Australia/Sydney";
            case "new york", "nyc" -> "America/New_York";
            case "los angeles", "la" -> "America/Los_Angeles";
            case "berlin" -> "Europe/Berlin";
            case "mumbai" -> "Asia/Kolkata";
            case "beijing" -> "Asia/Shanghai";
            default -> "UTC";
        };
        return city + ": " + ZonedDateTime.now(ZoneId.of(zone))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss (z)"));
    }

    @AiTool(name = "get_weather",
            description = "Returns a weather report for a city with temperature and conditions")
    public String getWeather(
            @Param(value = "city", description = "City name to get weather for")
            String city) {
        // Simulated weather — in production, call a real weather API
        return switch (city.toLowerCase()) {
            case "london" -> "London: Cloudy, 15°C / 59°F, 80% humidity";
            case "paris" -> "Paris: Partly cloudy, 20°C / 68°F, 65% humidity";
            case "tokyo" -> "Tokyo: Rainy, 22°C / 72°F, 90% humidity";
            case "sydney" -> "Sydney: Clear, 28°C / 82°F, 45% humidity";
            case "new york", "nyc" -> "New York: Sunny, 25°C / 77°F, 55% humidity";
            case "los angeles", "la" -> "Los Angeles: Sunny, 30°C / 86°F, 30% humidity";
            default -> city + ": Clear, 22°C / 72°F, 50% humidity";
        };
    }

    @AiTool(name = "convert_temperature",
            description = "Converts a temperature between Celsius and Fahrenheit")
    public String convertTemperature(
            @Param(value = "value", description = "The temperature value to convert")
            double value,
            @Param(value = "from_unit", description = "Source unit: 'C' for Celsius or 'F' for Fahrenheit")
            String fromUnit) {
        if ("C".equalsIgnoreCase(fromUnit) || "celsius".equalsIgnoreCase(fromUnit)) {
            double fahrenheit = value * 9.0 / 5.0 + 32;
            return String.format("%.1f°C = %.1f°F", value, fahrenheit);
        } else {
            double celsius = (value - 32) * 5.0 / 9.0;
            return String.format("%.1f°F = %.1f°C", value, celsius);
        }
    }
}
