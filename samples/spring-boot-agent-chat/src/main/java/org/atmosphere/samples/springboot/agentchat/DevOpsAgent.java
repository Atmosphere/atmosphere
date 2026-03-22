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
package org.atmosphere.samples.springboot.agentchat;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DevOps agent demonstrating the {@code @Agent} annotation with slash commands,
 * AI tools, and a skill file for the system prompt.
 *
 * <p>Features demonstrated:</p>
 * <ul>
 *   <li>{@code @Agent} — registers at {@code /atmosphere/agent/devops}</li>
 *   <li>{@code @Command} — {@code /status}, {@code /deploy}, {@code /uptime}, {@code /incidents}</li>
 *   <li>{@code @AiTool} — {@code check_service}, {@code get_metrics}</li>
 *   <li>{@code @Prompt} — delegates to LLM or demo mode</li>
 *   <li>Skill file — {@code prompts/devops-skill.md}</li>
 * </ul>
 */
@Agent(name = "devops",
        skillFile = "prompts/devops-skill.md",
        description = "DevOps assistant with service monitoring, deployment, and incident management")
public class DevOpsAgent {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsAgent.class);
    private static final Instant START_TIME = Instant.now();
    private static final Map<String, String> SERVICE_STATUS = new ConcurrentHashMap<>(Map.of(
            "api-gateway", "healthy",
            "user-service", "healthy",
            "payment-service", "healthy",
            "notification-service", "degraded"
    ));

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to DevOps agent", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected from DevOps agent", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Received prompt: {}", message);
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }
        session.stream(message);
    }

    // --- Slash Commands ---

    @Command(value = "/status", description = "Show status of all services")
    public String status() {
        var sb = new StringBuilder("Service Status:\n");
        SERVICE_STATUS.forEach((service, status) ->
                sb.append("  ").append(service).append(": ").append(status).append("\n"));
        return sb.toString().trim();
    }

    @Command(value = "/deploy",
            description = "Deploy a service to staging",
            confirm = "Deploy to staging environment?")
    public String deploy(String args) {
        if (args == null || args.isBlank()) {
            return "Usage: /deploy <service-name> [version]";
        }
        var parts = args.split("\\s+", 2);
        var service = parts[0];
        var version = parts.length > 1 ? parts[1] : "latest";
        logger.info("Deploying {} version {} to staging", service, version);
        return "Deployed " + service + " (version: " + version + ") to staging. "
                + "Build pipeline triggered.";
    }

    @Command(value = "/uptime", description = "Show agent uptime")
    public String uptime() {
        var elapsed = java.time.Duration.between(START_TIME, Instant.now());
        return "Agent uptime: " + elapsed.toHours() + "h " + elapsed.toMinutesPart() + "m "
                + elapsed.toSecondsPart() + "s";
    }

    @Command(value = "/incidents", description = "List active incidents")
    public String incidents() {
        var degraded = SERVICE_STATUS.entrySet().stream()
                .filter(e -> !"healthy".equals(e.getValue()))
                .toList();
        if (degraded.isEmpty()) {
            return "No active incidents. All services healthy.";
        }
        var sb = new StringBuilder("Active incidents:\n");
        for (var entry : degraded) {
            sb.append("  [").append(entry.getValue().toUpperCase()).append("] ")
                    .append(entry.getKey()).append("\n");
        }
        return sb.toString().trim();
    }

    // --- AI Tools ---

    @AiTool(name = "check_service", description = "Check the health status of a specific service")
    public String checkService(@Param(value = "service", description = "Service name to check") String service) {
        var status = SERVICE_STATUS.get(service);
        if (status == null) {
            return "Unknown service: " + service + ". Known services: " + SERVICE_STATUS.keySet();
        }
        return service + " is " + status + " (checked at " + formatNow() + ")";
    }

    @AiTool(name = "get_metrics", description = "Get performance metrics for a service")
    public String getMetrics(@Param(value = "service", description = "Service name") String service,
                             @Param(value = "metric", description = "Metric type: cpu, memory, latency, errors") String metric) {
        // Simulated metrics
        return switch (metric.toLowerCase()) {
            case "cpu" -> service + " CPU: 42% (avg 5m)";
            case "memory" -> service + " Memory: 1.2 GB / 4 GB (30%)";
            case "latency" -> service + " P99 latency: 145ms, P50: 12ms";
            case "errors" -> service + " Error rate: 0.02% (last hour)";
            default -> "Unknown metric: " + metric + ". Available: cpu, memory, latency, errors";
        };
    }

    private String formatNow() {
        return DateTimeFormatter.ofPattern("HH:mm:ss z")
                .format(Instant.now().atZone(ZoneId.systemDefault()));
    }
}
