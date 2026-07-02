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
package org.atmosphere.samples.springboot.agui;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Real AG-UI agent. The {@code @Agent} annotation desugars to an Atmosphere AI
 * endpoint and — because {@code atmosphere-agui} is on the classpath — also
 * auto-registers an AG-UI SSE endpoint at
 * {@code /atmosphere/agent/assistant/agui}.
 *
 * <p>A user message posted to that endpoint flows:</p>
 * <pre>
 * POST /atmosphere/agent/assistant/agui
 *   → AgUiHandler parses the RunContext, emits RUN_STARTED
 *   → invokes {@link #onPrompt(String, StreamingSession)} on a virtual thread
 *   → session.stream(message) drives the real AiPipeline (LLM + @AiTool dispatch)
 *   → each AiEvent is mapped to AG-UI frames by AgUiEventMapper and flushed as SSE
 *   → RUN_FINISHED on completion
 * </pre>
 *
 * <p>When no LLM key is configured ({@link AiConfig#get()} is {@code null} or
 * has a blank {@code apiKey()}), {@link #onPrompt} falls back to
 * {@link DemoResponseProducer} so the sample streams a real AG-UI event sequence
 * out of the box. Set {@code LLM_API_KEY} (or {@code GEMINI_API_KEY} /
 * {@code OPENAI_API_KEY}) to drive a real model and exercise tool calling.</p>
 */
@AgentScope(unrestricted = true,
        justification = "AG-UI protocol demo assistant; accepts arbitrary prompts to showcase RunStarted/RunFinished streaming")
@Agent(
        name = "assistant",
        skillFile = "skill:agui-assistant",
        description = "AG-UI assistant that streams real AgentRuntime output as AG-UI events, "
                + "with get_weather / get_time tools and a no-key demo fallback.",
        version = "1.0.0")
public class AssistantAgent {

    private static final Logger logger = LoggerFactory.getLogger(AssistantAgent.class);

    /**
     * Handles a user prompt. Drives the real pipeline when a key is configured,
     * otherwise streams a demo response — mirroring {@code spring-boot-ai-chat}'s
     * no-key contract so the AG-UI event sequence is identical in both modes.
     */
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("AG-UI prompt: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        session.stream(message);
    }

    /**
     * Real {@code @AiTool} the runtime dispatches when the model decides a weather
     * lookup is needed. The body returns illustrative canned data (this sample
     * demonstrates tool-calling wiring, not a live weather service) — replace it
     * with a real weather API call to make it production-grade.
     */
    @AiTool(name = "get_weather",
            description = "Returns a short weather report for a city with temperature and conditions")
    public String getWeather(
            @Param(value = "city", description = "City name to get weather for")
            String city) {
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

    /**
     * Real {@code @AiTool} the runtime dispatches for time queries. Returns the
     * current time in the requested IANA timezone (defaults to the server zone
     * when the timezone is blank or invalid).
     */
    @AiTool(name = "get_time",
            description = "Returns the current date and time for an IANA timezone "
                    + "(e.g. America/New_York, Europe/Paris, Asia/Tokyo)")
    public String getTime(
            @Param(value = "timezone", description = "IANA timezone id, or blank for the server timezone")
            String timezone) {
        var zone = ZoneId.systemDefault();
        if (timezone != null && !timezone.isBlank()) {
            try {
                zone = ZoneId.of(timezone.trim());
            } catch (DateTimeException e) {
                logger.debug("Invalid timezone '{}', using server default", timezone, e);
            }
        }
        return ZonedDateTime.now(zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }
}
