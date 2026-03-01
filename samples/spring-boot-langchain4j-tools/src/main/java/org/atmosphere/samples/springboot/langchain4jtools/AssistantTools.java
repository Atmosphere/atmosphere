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

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Tool methods discoverable by LangChain4j's AI Service.
 *
 * <p>Ported from the official langchain4j-examples {@code AssistantTools} class,
 * extended with additional tools to demonstrate tool calling through Atmosphere.</p>
 */
@Component
public class AssistantTools {

    private static final Logger logger = LoggerFactory.getLogger(AssistantTools.class);

    private static final Map<String, String> CITY_TIMEZONES = Map.of(
            "new york", "America/New_York",
            "london", "Europe/London",
            "paris", "Europe/Paris",
            "tokyo", "Asia/Tokyo",
            "sydney", "Australia/Sydney"
    );

    @Tool("Returns the current date and time")
    public String currentTime() {
        logger.info("Tool called: currentTime");
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool("Returns the current time in a specific city")
    public String cityTime(String city) {
        logger.info("Tool called: cityTime({})", city);
        var zone = CITY_TIMEZONES.get(city.toLowerCase().trim());
        if (zone == null) {
            return "Unknown city: " + city + ". Known cities: " + String.join(", ", CITY_TIMEZONES.keySet());
        }
        var time = LocalDateTime.now(ZoneId.of(zone))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return "The current time in " + city + " is " + time;
    }

    @Tool("Returns a simulated weather report for a city")
    public String weather(String city) {
        logger.info("Tool called: weather({})", city);
        return switch (city.toLowerCase().trim()) {
            case "new york" -> "New York: Sunny, 25°C / 77°F";
            case "london" -> "London: Cloudy, 15°C / 59°F";
            case "paris" -> "Paris: Partly cloudy, 20°C / 68°F";
            case "tokyo" -> "Tokyo: Rainy, 22°C / 72°F";
            case "sydney" -> "Sydney: Clear, 28°C / 82°F";
            default -> "Weather data not available for " + city;
        };
    }
}
