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

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.agui.event.AgUiEvent;
import org.atmosphere.agui.event.AgUiEventMapper;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.ai.AiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * AG-UI endpoint served as a Spring MVC controller. AG-UI uses POST-based SSE
 * which bypasses Atmosphere's WebSocket/SSE transport — a plain servlet response
 * with chunked streaming is the correct fit.
 */
@RestController
public class AssistantAgent {

    private static final Logger logger = LoggerFactory.getLogger(AssistantAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping(value = "/agui", produces = "text/event-stream")
    public void onRun(@RequestBody RunContext run, HttpServletResponse response)
            throws IOException {
        var runId = run.runId() != null ? run.runId() : UUID.randomUUID().toString();
        var threadId = run.threadId() != null ? run.threadId() : UUID.randomUUID().toString();
        var message = run.lastUserMessage();

        logger.info("AG-UI run {} started, message: {}", runId, message);

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        var writer = response.getWriter();
        var eventMapper = new AgUiEventMapper();

        // RunStarted
        writeSSE(writer, new AgUiEvent.RunStarted(runId, threadId));

        // Step 1: Thinking
        emitAiEvent(writer, eventMapper, new AiEvent.AgentStep("analyze", "Analyzing your request...", Map.of()));
        sleep(200);

        // Tool calls if needed
        var lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("weather")) {
            emitAiEvent(writer, eventMapper, new AiEvent.ToolStart("get_weather", Map.of("query", message)));
            sleep(300);
            int temp = 18 + (int) (Math.random() * 15);
            String[] conditions = {"sunny", "partly cloudy", "overcast", "light rain"};
            String condition = conditions[(int) (Math.random() * conditions.length)];
            var result = String.format("{\"temp\": %d, \"condition\": \"%s\", \"humidity\": %d}",
                    temp, condition, 40 + (int) (Math.random() * 40));
            emitAiEvent(writer, eventMapper, new AiEvent.ToolResult("get_weather", result));
        } else if (lowerMessage.contains("time")) {
            emitAiEvent(writer, eventMapper, new AiEvent.ToolStart("get_time", Map.of()));
            sleep(100);
            var now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            emitAiEvent(writer, eventMapper, new AiEvent.ToolResult("get_time", now));
        }

        // Step 2: Respond
        emitAiEvent(writer, eventMapper, new AiEvent.AgentStep("respond", "Generating response...", Map.of()));
        sleep(100);

        // Stream text word by word
        var text = generateResponse(message);
        for (var word : text.split(" ")) {
            emitAiEvent(writer, eventMapper, new AiEvent.TextDelta(word + " "));
            sleep(50);
        }
        emitAiEvent(writer, eventMapper, new AiEvent.TextComplete(text));

        // RunFinished
        writeSSE(writer, new AgUiEvent.RunFinished(runId, threadId));
        writer.close();
    }

    private void emitAiEvent(PrintWriter writer, AgUiEventMapper mapper, AiEvent event) {
        for (var agUiEvent : mapper.toAgUi(event)) {
            writeSSE(writer, agUiEvent);
        }
    }

    private void writeSSE(PrintWriter writer, AgUiEvent event) {
        try {
            var json = MAPPER.writeValueAsString(event);
            writer.write("event: " + event.type() + "\ndata: " + json + "\n\n");
            writer.flush();
        } catch (Exception e) {
            logger.debug("Failed to write SSE event", e);
        }
    }

    private String generateResponse(String message) {
        var lower = message.toLowerCase();
        if (lower.contains("weather")) {
            return "Based on the current conditions, it looks like a pleasant day. "
                    + "The temperature is mild and comfortable for outdoor activities.";
        } else if (lower.contains("time")) {
            return "I've checked the current time for you. "
                    + "The time shown above is in your local timezone.";
        } else if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm an AI assistant powered by Atmosphere's AG-UI protocol. "
                    + "I can help with weather information, time queries, and general questions. "
                    + "Try asking about the weather or what time it is!";
        } else {
            return "I received your message and processed it through the AG-UI protocol. "
                    + "This demonstrates how Atmosphere streams AI agent state to frontends "
                    + "using Server-Sent Events, compatible with CopilotKit and similar frameworks.";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
