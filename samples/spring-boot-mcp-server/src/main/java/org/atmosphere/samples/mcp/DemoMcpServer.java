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
package org.atmosphere.samples.mcp;

import jakarta.inject.Inject;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpMessage;
import org.atmosphere.util.Version;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample MCP server that exposes tools, resources, and prompts to AI agents.
 * Includes chat administration tools that interact with the live {@code /atmosphere/chat} broadcaster.
 * Connect an MCP client (e.g., Claude Desktop) to {@code ws://localhost:8083/mcp}.
 */
@McpServer(name = "atmosphere-demo", version = "1.0.0", path = "/atmosphere/mcp")
public class DemoMcpServer {

    private static final String CHAT_PATH = "/atmosphere/chat";

    @Inject
    private AtmosphereConfig config;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> notes = new ConcurrentHashMap<>();
    private final AtomicInteger noteCounter = new AtomicInteger();

    // ── Tools ────────────────────────────────────────────────────────────

    @McpTool(name = "get_time", description = "Get the current server time in a given timezone")
    public String getTime(
            @McpParam(name = "timezone", description = "IANA timezone (e.g., America/New_York)", required = false) String timezone
    ) {
        var zone = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();
        return Instant.now().atZone(zone).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    @McpTool(name = "save_note", description = "Save a note with a title")
    public Map<String, Object> saveNote(
            @McpParam(name = "title", description = "Note title") String title,
            @McpParam(name = "content", description = "Note content") String content
    ) {
        var id = "note-" + noteCounter.incrementAndGet();
        notes.put(id, title + ": " + content);
        return Map.of("id", id, "message", "Note saved successfully");
    }

    @McpTool(name = "list_notes", description = "List all saved notes")
    public Map<String, String> listNotes() {
        return Map.copyOf(notes);
    }

    @McpTool(name = "calculate", description = "Evaluate a simple arithmetic expression")
    public Map<String, Object> calculate(
            @McpParam(name = "expression", description = "Arithmetic expression (e.g., '2 + 3 * 4')") String expression
    ) {
        // Simple calculator for demo purposes
        try {
            var result = evalSimple(expression);
            return Map.of("expression", expression, "result", result);
        } catch (Exception e) {
            return Map.of("expression", expression, "error", e.getMessage());
        }
    }

    // ── Chat Administration Tools ─────────────────────────────────────────

    @McpTool(name = "list_users", description = "List all users currently connected to the chat")
    public List<Map<String, String>> listUsers() {
        var broadcaster = chatBroadcaster();
        if (broadcaster == null) {
            return List.of(Map.of("error", "No chat broadcaster active"));
        }
        return broadcaster.getAtmosphereResources().stream()
                .map(r -> Map.of(
                        "uuid", r.uuid(),
                        "transport", r.transport().name()))
                .toList();
    }

    @McpTool(name = "ban_user", description = "Disconnect and ban a user from the chat by UUID")
    public Map<String, Object> banUser(
            @McpParam(name = "uuid", description = "UUID of the user to ban") String uuid
    ) {
        var resource = config.resourcesFactory().find(uuid);
        if (resource == null) {
            return Map.of("error", "User not found: " + uuid);
        }
        try {
            resource.close();
            return Map.of("status", "banned", "uuid", uuid);
        } catch (IOException e) {
            return Map.of("error", "Failed to ban user: " + e.getMessage());
        }
    }

    @McpTool(name = "broadcast_message", description = "Send a message to all connected chat users")
    public Map<String, Object> broadcastMessage(
            @McpParam(name = "message", description = "The message text to broadcast") String message,
            @McpParam(name = "author", description = "Author name for the message", required = false) String author
    ) {
        var broadcaster = chatBroadcaster();
        if (broadcaster == null) {
            return Map.of("error", "No chat broadcaster active");
        }
        var msg = new Message(author != null ? author : "MCP Admin", message);
        broadcaster.broadcast(mapper.writeValueAsString(msg));
        return Map.of("status", "sent", "recipients", broadcaster.getAtmosphereResources().size());
    }

    @McpTool(name = "send_message", description = "Send a private message to a specific chat user by UUID")
    public Map<String, Object> sendMessage(
            @McpParam(name = "uuid", description = "UUID of the target user") String uuid,
            @McpParam(name = "message", description = "The message text to send") String message,
            @McpParam(name = "author", description = "Author name for the message", required = false) String author
    ) {
        var resource = config.resourcesFactory().find(uuid);
        if (resource == null) {
            return Map.of("error", "User not found: " + uuid);
        }
        var broadcaster = chatBroadcaster();
        if (broadcaster == null) {
            return Map.of("error", "No chat broadcaster active");
        }
        var msg = new Message(author != null ? author : "MCP Admin", message);
        broadcaster.broadcast(mapper.writeValueAsString(msg), resource);
        return Map.of("status", "sent", "uuid", uuid);
    }

    @McpTool(name = "atmosphere_version", description = "Return the Atmosphere framework version and runtime info")
    public Map<String, Object> atmosphereVersion() {
        var info = new LinkedHashMap<String, Object>();
        info.put("version", Version.getRawVersion());
        info.put("asyncSupport", config.framework().getAsyncSupport().getClass().getSimpleName());
        info.put("broadcasters", config.getBroadcasterFactory().lookupAll().size());
        info.put("connectedResources", config.resourcesFactory().findAll().size());
        info.put("javaVersion", System.getProperty("java.version"));
        return info;
    }

    // ── Resources ────────────────────────────────────────────────────────

    @McpResource(uri = "atmosphere://server/status",
            name = "Server Status",
            description = "Current server status and uptime",
            mimeType = "application/json")
    public String serverStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("status", "running");
        status.put("framework", "Atmosphere 4.0");
        status.put("transport", "WebSocket + SSE fallback");
        status.put("javaVersion", System.getProperty("java.version"));
        status.put("timestamp", Instant.now().toString());
        status.put("notesCount", notes.size());
        return status.toString();
    }

    @McpResource(uri = "atmosphere://server/capabilities",
            name = "Server Capabilities",
            description = "What this MCP server can do")
    public String capabilities() {
        return """
                This Atmosphere MCP server demonstrates:
                - Real-time tool invocation over WebSocket with SSE fallback
                - Automatic reconnection and message replay
                - Note-taking tools (save, list)
                - Server time across timezones
                - Simple arithmetic calculator
                - Prompt templates for data analysis
                """;
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @McpPrompt(name = "summarize_notes", description = "Summarize all saved notes")
    public List<McpMessage> summarizeNotes() {
        var noteList = notes.isEmpty() ? "No notes saved yet."
                : String.join("\n", notes.values());
        return List.of(
                McpMessage.system("You are a helpful assistant that summarizes notes concisely."),
                McpMessage.user("Please summarize these notes:\n" + noteList)
        );
    }

    @McpPrompt(name = "analyze_topic", description = "Get an AI analysis of a topic")
    public List<McpMessage> analyzeTopic(
            @McpParam(name = "topic", description = "Topic to analyze") String topic,
            @McpParam(name = "depth", description = "Analysis depth: brief, moderate, or deep", required = false) String depth
    ) {
        var depthStr = depth != null ? depth : "moderate";
        return List.of(
                McpMessage.system("You are an expert analyst. Provide a " + depthStr + " analysis."),
                McpMessage.user("Analyze the following topic: " + topic)
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Broadcaster chatBroadcaster() {
        try {
            return config.getBroadcasterFactory().lookup(CHAT_PATH, false);
        } catch (Exception e) {
            return null;
        }
    }

    private double evalSimple(String expr) {
        // Very basic: supports + - * / on two numbers
        expr = expr.trim();
        for (char op : new char[]{'+', '-', '*', '/'}) {
            var idx = expr.lastIndexOf(op);
            if (idx > 0) {
                var left = Double.parseDouble(expr.substring(0, idx).trim());
                var right = Double.parseDouble(expr.substring(idx + 1).trim());
                return switch (op) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    case '*' -> left * right;
                    case '/' -> left / right;
                    default -> throw new IllegalArgumentException("Unknown operator: " + op);
                };
            }
        }
        return Double.parseDouble(expr);
    }
}
