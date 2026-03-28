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
package org.atmosphere.samples.springboot.a2a;

import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI-powered A2A agent that answers questions, provides weather info, and
 * handles time queries. When an LLM API key is configured (via {@code LLM_API_KEY}
 * environment variable or {@code llm.api-key} property), the agent uses the real
 * LLM for responses. Without a key, it falls back to deterministic demo responses.
 */
@Agent(
    name = "atmosphere-assistant",
    description = "AI-powered assistant that answers questions, provides weather info, "
            + "and handles time queries. Uses Gemini/OpenAI/Ollama when configured, "
            + "with built-in demo fallback.",
    version = "1.0.0",
    endpoint = "/atmosphere/a2a"
)
public class WeatherTimeAgent {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTimeAgent.class);

    @AgentSkill(id = "ask", name = "Ask Assistant",
              description = "Ask the AI assistant any question. Uses LLM when API key is configured, otherwise provides demo responses.",
              tags = {"ai", "general", "chat"})
    @AgentSkillHandler
    public void ask(TaskContext task,
                    @AgentSkillParam(name = "message", description = "The question or request") String message) {
        task.updateStatus(TaskState.WORKING, "Processing your request...");
        task.addMessage(Message.user(message));

        try {
            var settings = AiConfig.get();
            if (settings != null && settings.apiKey() != null && !settings.apiKey().isBlank()) {
                // Real LLM call
                var response = callLlm(settings, message);
                task.addArtifact(Artifact.text(response));
                task.addMessage(Message.agent(response));
                task.complete("Response generated via " + settings.model());
            } else {
                // Demo fallback
                var response = demoResponse(message);
                task.addArtifact(Artifact.text(response));
                task.addMessage(Message.agent(response));
                task.complete("Demo response (configure LLM_API_KEY for real AI)");
            }
        } catch (Exception e) {
            logger.error("Failed to process request", e);
            task.fail("Error: " + e.getMessage());
        }
    }

    @AgentSkill(id = "get-weather", name = "Get Weather",
              description = "Get weather for a location. With LLM configured, provides "
                      + "AI-generated weather analysis. Without, returns simulated data.",
              tags = {"weather", "information"})
    @AgentSkillHandler
    public void getWeather(TaskContext task,
                           @AgentSkillParam(name = "location", description = "City or location name") String location) {
        task.updateStatus(TaskState.WORKING, "Checking weather for " + location + "...");

        try {
            var settings = AiConfig.get();
            if (settings != null && settings.apiKey() != null && !settings.apiKey().isBlank()) {
                var prompt = "Give a brief, realistic current weather report for " + location
                        + ". Include temperature, conditions, humidity, and a short forecast. Be concise (2-3 sentences).";
                var response = callLlm(settings, prompt);
                task.addArtifact(Artifact.text(response));
                task.complete("AI weather report for " + location);
            } else {
                // Deterministic demo weather
                int hash = Math.abs(location.hashCode());
                int temp = 15 + (hash % 25);
                int humidity = 30 + (hash % 60);
                String[] conditions = {"Sunny", "Partly Cloudy", "Cloudy", "Rainy", "Windy"};
                String condition = conditions[hash % conditions.length];

                var report = String.format("Weather for %s: %d\u00B0C, %s, Humidity: %d%%. "
                        + "(Demo mode \u2014 set LLM_API_KEY for real AI weather analysis)",
                        location, temp, condition, humidity);
                task.addArtifact(Artifact.text(report));
                task.complete("Demo weather for " + location);
            }
        } catch (Exception e) {
            logger.error("Weather query failed", e);
            task.fail("Weather query failed: " + e.getMessage());
        }
    }

    @AgentSkill(id = "get-time", name = "Get Time",
              description = "Returns the current date and time in any timezone",
              tags = {"time", "timezone", "utility"})
    @AgentSkillHandler
    public void getTime(TaskContext task,
                        @AgentSkillParam(name = "timezone",
                                description = "IANA timezone (e.g., America/New_York, "
                                        + "Europe/Paris, Asia/Tokyo)") String timezone) {
        task.updateStatus(TaskState.WORKING, "Looking up time...");

        try {
            var zoneId = ZoneId.of(timezone);
            var now = ZonedDateTime.now(zoneId);
            var formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            task.addArtifact(Artifact.text("Current time in " + timezone + ": " + formatted));
            task.complete("Time retrieved for " + timezone);
        } catch (DateTimeException e) {
            task.fail("Invalid timezone: " + timezone + ". Use IANA format like America/New_York");
        }
    }

    /**
     * Calls the LLM using the OpenAI-compatible streaming API and collects
     * the full response. Uses a lightweight {@link StreamingSession} implementation
     * that buffers all streamed text chunks into a single string.
     */
    private String callLlm(AiConfig.LlmSettings settings, String prompt) {
        var request = ChatCompletionRequest.of(settings.model(), prompt);
        var collector = new CollectingSession();
        settings.client().streamChatCompletion(request, collector);

        if (collector.error != null) {
            throw new RuntimeException("LLM call failed: " + collector.error.getMessage(), collector.error);
        }
        return collector.buffer.toString();
    }

    private String demoResponse(String message) {
        var lower = message.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm the Atmosphere A2A assistant agent. I can answer questions, "
                    + "check weather, and tell time across timezones. "
                    + "Configure LLM_API_KEY to enable real AI responses.";
        } else if (lower.contains("weather")) {
            return "For weather queries, please use the 'get-weather' skill with a specific location. "
                    + "Example: {\"skillId\": \"get-weather\", \"location\": \"Montreal\"}";
        } else if (lower.contains("time")) {
            return "For time queries, please use the 'get-time' skill with a timezone. "
                    + "Example: {\"skillId\": \"get-time\", \"timezone\": \"America/New_York\"}";
        } else {
            return "I received your message: \"" + message + "\". "
                    + "This is a demo response. Configure LLM_API_KEY (Gemini, OpenAI, or Ollama) "
                    + "to get real AI-powered answers via the A2A protocol.";
        }
    }

    /**
     * Minimal {@link StreamingSession} that collects all streamed text chunks
     * into a {@link StringBuilder} for synchronous use in A2A task handlers.
     */
    private static class CollectingSession implements StreamingSession {

        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile Throwable error;
        private final String sessionId = UUID.randomUUID().toString();

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public void send(String text) {
            buffer.append(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // Metadata not needed for response collection
        }

        @Override
        public void progress(String message) {
            logger.debug("LLM progress: {}", message);
        }

        @Override
        public void complete() {
            closed.set(true);
        }

        @Override
        public void complete(String summary) {
            closed.set(true);
        }

        @Override
        public void error(Throwable t) {
            this.error = t;
            closed.set(true);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }
}
