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
package org.atmosphere.samples.springboot.a2astartup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CEO Coordinator Agent — discovers specialist agents via A2A Agent Cards
 * at runtime and delegates tasks via HTTP JSON-RPC 2.0.
 *
 * <p>This is a TRUE multi-agent coordinator:</p>
 * <ul>
 *   <li>Uses {@code @Agent} for WebSocket UI (browser clients)</li>
 *   <li>Discovers specialists via HTTP GET to their Agent Card endpoints</li>
 *   <li>Delegates via A2A {@code message/send} JSON-RPC calls</li>
 *   <li>Each specialist is an independent headless {@code @Agent}</li>
 * </ul>
 */
@Agent(name = "ceo",
        skillFile = "prompts/ceo-skill.md",
        description = "Startup CEO that coordinates specialist A2A agents for market analysis")
public class CeoAgent {

    private static final Logger logger = LoggerFactory.getLogger(CeoAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** A2A agent endpoints to discover — resolved at runtime via Agent Cards. */
    private static final List<String> AGENT_ENDPOINTS = List.of(
            "/atmosphere/a2a/research",
            "/atmosphere/a2a/strategy",
            "/atmosphere/a2a/finance",
            "/atmosphere/a2a/writer"
    );

    private static int serverPort = 0;

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to CEO", resource.uuid());
        if (serverPort == 0) {
            serverPort = detectPort(resource);
            logger.info("A2A delegation will use port {}", serverPort);
        }
    }

    private static int detectPort(AtmosphereResource resource) {
        // 1. Try SERVER_PORT env var (set by docker-compose, CLI, etc.)
        var envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) { }
        }
        // 2. Try servlet request's serverPort (from Host header)
        var req = resource.getRequest();
        var port = req.getServerPort();
        if (port > 0 && port != 80 && port != 443) {
            return port;
        }
        // 3. Try localPort (actual connector port)
        port = req.getLocalPort();
        if (port > 0 && port != 80 && port != 443) {
            return port;
        }
        return 8080;
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("CEO received: {}", message);
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        // Step 1: Discover all specialist agents via Agent Cards
        var agents = discoverAgents();
        if (agents.isEmpty()) {
            session.send("No specialist agents discovered. Check server logs.");
            session.complete();
            return;
        }
        logger.info("Discovered {} specialist agents", agents.size());

        // Step 2: Delegate to each specialist via A2A message/send
        var agentResults = new LinkedHashMap<String, String>();

        // Research Agent
        var researchResult = delegateToAgent("research", "web_search",
                Map.of("query", message, "num_results", "3"), session);
        agentResults.put("research", researchResult);

        // Strategy Agent
        var strategyResult = delegateToAgent("strategy", "analyze_strategy",
                Map.of("market", message, "research_findings", researchResult, "focus_area", "market entry"), session);
        agentResults.put("strategy", strategyResult);

        // Finance Agent
        var financeResult = delegateToAgent("finance", "financial_model",
                Map.of("market", message, "tam_estimate", "15", "growth_rate", "35",
                        "pricing_model", "SaaS $29/mo per seat"), session);
        agentResults.put("finance", financeResult);

        // Writer Agent
        var writerResult = delegateToAgent("writer", "write_report",
                Map.of("title", message + " — Market Analysis",
                        "key_findings", researchResult + "\n" + strategyResult,
                        "recommendation", "Comprehensive analysis with financial projections"), session);
        agentResults.put("writer", writerResult);

        // Step 3: CEO synthesis via LLM
        var synthesisPrompt = String.format(
                "You are a startup CEO synthesizing findings from your team of specialist agents. "
                + "Each agent worked independently via the A2A protocol. Based on their reports, "
                + "write a concise executive briefing with: 1) Executive Summary (3 bullets), "
                + "2) GO/NO-GO recommendation with confidence level, 3) Key risks, "
                + "4) Recommended next steps.\n\n"
                + "RESEARCH AGENT:\n%s\n\nSTRATEGY AGENT:\n%s\n\nFINANCE AGENT:\n%s\n\n"
                + "Be concise and decisive. Use markdown.",
                agentResults.get("research"), agentResults.get("strategy"), agentResults.get("finance"));
        session.stream(synthesisPrompt);
    }

    /**
     * Discover specialist agents by fetching their Agent Cards via HTTP.
     */
    private List<AgentInfo> discoverAgents() {
        var agents = new ArrayList<AgentInfo>();
        for (var endpoint : AGENT_ENDPOINTS) {
            try {
                var cardUrl = "http://127.0.0.1:" + serverPort + endpoint;
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(cardUrl))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent/authenticatedExtendedCard\"}"))
                        .header("Content-Type", "application/json")
                        .build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var json = mapper.readTree(response.body());
                    var result = json.get("result");
                    if (result != null) {
                        var name = result.has("name") ? result.get("name").asText() : "unknown";
                        var skills = new ArrayList<String>();
                        if (result.has("skills")) {
                            for (var skill : result.get("skills")) {
                                skills.add(skill.get("id").asText());
                            }
                        }
                        agents.add(new AgentInfo(name, endpoint, skills));
                        logger.info("Discovered agent: {} at {} with skills: {}", name, endpoint, skills);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to discover agent at {}: {}", endpoint, e.getMessage());
            }
        }
        return agents;
    }

    /**
     * Delegate a task to a specialist agent via A2A JSON-RPC {@code message/send}.
     */
    private String delegateToAgent(String agentName, String skillId,
                                   Map<String, String> arguments, StreamingSession session) {
        var endpoint = "/atmosphere/a2a/" + agentName;
        var toolArgs = new LinkedHashMap<String, Object>(arguments);

        // Emit tool-start event so the frontend shows the agent card
        session.emit(new AiEvent.ToolStart(skillId, toolArgs));

        try {
            // Build A2A JSON-RPC request
            var messageObj = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("type", "text", "text", arguments.values().iterator().next())),
                    "metadata", Map.of("skillId", skillId)
            );
            var params = new LinkedHashMap<String, Object>();
            params.put("message", messageObj);
            params.put("arguments", arguments);

            var rpcRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "message/send",
                    "params", params
            );

            var requestBody = mapper.writeValueAsString(rpcRequest);
            var url = "http://127.0.0.1:" + serverPort + endpoint;

            logger.info("CEO -> {} via A2A: skillId={}", agentName, skillId);

            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", "application/json")
                    .build();
            var response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var json = mapper.readTree(response.body());
                var result = json.get("result");
                if (result != null) {
                    var artifacts = result.get("artifacts");
                    if (artifacts != null && artifacts.isArray() && !artifacts.isEmpty()) {
                        var parts = artifacts.get(0).get("parts");
                        if (parts != null && parts.isArray() && !parts.isEmpty()) {
                            var text = parts.get(0).has("text") ? parts.get(0).get("text").asText() : "";
                            session.emit(new AiEvent.ToolResult(skillId, text));
                            return text;
                        }
                    }
                }
                // Fallback: return raw response
                var raw = response.body();
                session.emit(new AiEvent.ToolResult(skillId, raw));
                return raw;
            } else {
                var error = "A2A call failed: HTTP " + response.statusCode();
                session.emit(new AiEvent.ToolResult(skillId, error));
                return error;
            }
        } catch (Exception e) {
            var error = "A2A delegation failed: " + e.getMessage();
            logger.error("CEO delegation to {} failed", agentName, e);
            session.emit(new AiEvent.ToolResult(skillId, error));
            return error;
        }
    }

    record AgentInfo(String name, String endpoint, List<String> skills) {}
}
