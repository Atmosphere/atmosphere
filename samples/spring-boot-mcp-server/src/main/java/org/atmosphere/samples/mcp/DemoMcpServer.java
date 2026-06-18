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
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.protocol.McpMessage;
import org.atmosphere.util.Version;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample MCP server that exposes tools, resources, and prompts to AI agents.
 * Includes chat administration tools that interact with the live {@code /atmosphere/chat} broadcaster.
 * Connect an MCP client (e.g., Claude Desktop) to {@code ws://localhost:8083/mcp}.
 */
@Agent(name = "atmosphere-demo", version = "1.0.0", endpoint = "/atmosphere/mcp", headless = true)
public class DemoMcpServer {

    private static final String CHAT_PATH = "/atmosphere/chat";
    private static final String CHAT_SUMMARY_SYSTEM_PROMPT = org.atmosphere.ai.PromptLoader.resolve("skill:mcp-chat-summary");
    private static final String ANALYZE_TOPIC_SYSTEM_PROMPT = org.atmosphere.ai.PromptLoader.resolve("skill:mcp-analyze-topic");

    @Inject
    private AtmosphereConfig config;

    private final ObjectMapper mapper = new ObjectMapper();

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
        var resource = config.resourcesFactory().findResource(uuid);
        if (resource.isEmpty()) {
            return Map.of("error", "User not found: " + uuid);
        }
        try {
            resource.get().close();
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
        var resource = config.resourcesFactory().findResource(uuid);
        if (resource.isEmpty()) {
            return Map.of("error", "User not found: " + uuid);
        }
        var broadcaster = chatBroadcaster();
        if (broadcaster == null) {
            return Map.of("error", "No chat broadcaster active");
        }
        var msg = new Message(author != null ? author : "MCP Admin", message);
        broadcaster.broadcast(mapper.writeValueAsString(msg), resource.get());
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
    public String serverStatus() throws Exception {
        var status = new LinkedHashMap<String, Object>();
        status.put("status", "running");
        status.put("framework", "Atmosphere " + Version.getRawVersion());
        status.put("transport", "WebSocket + SSE fallback");
        status.put("javaVersion", System.getProperty("java.version"));
        status.put("timestamp", Instant.now().toString());
        status.put("connectedUsers", config.resourcesFactory().findAll().size());
        return mapper.writeValueAsString(status);
    }

    @McpResource(uri = "atmosphere://server/capabilities",
            name = "Server Capabilities",
            description = "What this MCP server can do")
    public String capabilities() {
        return """
                This Atmosphere MCP server demonstrates:
                - Real-time chat administration via MCP tools
                - List connected users, broadcast messages, ban users
                - WebSocket transport with SSE fallback
                - Automatic reconnection and message replay
                - An interactive MCP App (SEP-1865) rendered in the console
                """;
    }

    // ── MCP App (SEP-1865) ────────────────────────────────────────────────
    // A tool that declares a ui:// resource: the host (here, the Atmosphere
    // console) fetches the HTML and renders it in a sandboxed iframe.

    static final String CLOCK_APP_URI = "ui://atmosphere/clock-app.html";

    @McpTool(name = "clock_app", uiResource = CLOCK_APP_URI,
            title = "Server Clock",
            description = "An interactive live clock, rendered as an MCP App in the host UI")
    public Map<String, Object> clockApp() {
        // The tool result is delivered to the app; the app itself is the ui://
        // resource below. Keeping it tiny: just the server's current time.
        return Map.of("serverTime", Instant.now().toString());
    }

    @McpResource(uri = CLOCK_APP_URI,
            name = "Clock App UI",
            description = "Self-contained interactive clock (MCP App)",
            mimeType = "text/html;profile=mcp-app")
    public String clockAppHtml() {
        return CLOCK_APP_HTML;
    }

    private static final String CLOCK_APP_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>Atmosphere Clock</title>
              <style>
                html,body{margin:0;height:100%;font-family:system-ui,-apple-system,sans-serif}
                .app{height:100%;display:flex;flex-direction:column;align-items:center;
                     justify-content:center;gap:8px;
                     background:linear-gradient(135deg,#0f172a,#1e3a8a);color:#e2e8f0}
                .label{font-size:13px;letter-spacing:.18em;text-transform:uppercase;opacity:.7}
                .time{font-size:48px;font-weight:700;font-variant-numeric:tabular-nums}
                button{margin-top:14px;padding:8px 16px;font-size:13px;border:0;border-radius:6px;
                       background:#38bdf8;color:#06283d;font-weight:600;cursor:pointer}
                .result{margin-top:10px;font-size:13px;opacity:.85;min-height:18px}
              </style>
            </head>
            <body>
              <div class="app">
                <div class="label" data-testid="mcp-app-label">Atmosphere MCP App</div>
                <div class="time" id="clock" data-testid="mcp-clock">--:--:--</div>
                <button id="fetch" data-testid="fetch-version">Fetch server version</button>
                <div class="result" id="result" data-testid="bridge-result"></div>
              </div>
              <script>
                function tick() {
                  document.getElementById('clock').textContent =
                    new Date().toLocaleTimeString();
                }
                tick();
                setInterval(tick, 1000);

                // App Bridge (SEP-1865): JSON-RPC 2.0 over postMessage to the host.
                let nextId = 0;
                function sendRequest(method, params) {
                  const id = ++nextId;
                  return new Promise((resolve, reject) => {
                    window.addEventListener('message', function listener(event) {
                      const d = event.data;
                      // Only our matching response — never a host-initiated
                      // request (those carry a method and are handled below).
                      if (!d || d.id !== id || typeof d.method === 'string') return;
                      window.removeEventListener('message', listener);
                      if (d.error) reject(new Error(d.error.message || 'bridge error'));
                      else resolve(d.result);
                    });
                    window.parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
                  });
                }
                function notify(method, params) {
                  window.parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
                }
                function reply(id, result) {
                  window.parent.postMessage({ jsonrpc: '2.0', id, result }, '*');
                }
                function replyErr(id, code, message) {
                  window.parent.postMessage({ jsonrpc: '2.0', id, error: { code, message } }, '*');
                }

                // ── App-registered tools (Host -> App, SEP-1865) ──────────
                // This app exposes a tool the HOST can call back into it. The
                // host lists it via tools/list and invokes it via tools/call;
                // the app handles both as the server of the JSON-RPC channel.
                var THEMES = [
                  { name: 'Blue',   a: '#0f172a', b: '#1e3a8a', accent: '#38bdf8', text: '#06283d' },
                  { name: 'Purple', a: '#3b0764', b: '#7e22ce', accent: '#e9d5ff', text: '#3b0764' },
                  { name: 'Green',  a: '#064e3b', b: '#047857', accent: '#6ee7b7', text: '#064e3b' },
                  { name: 'Amber',  a: '#7c2d12', b: '#c2410c', accent: '#fed7aa', text: '#7c2d12' }
                ];
                var themeIdx = 0;
                function applyTheme(i) {
                  var t = THEMES[i % THEMES.length];
                  document.querySelector('.app').style.background =
                    'linear-gradient(135deg,' + t.a + ',' + t.b + ')';
                  var btn = document.getElementById('fetch');
                  btn.style.background = t.accent;
                  btn.style.color = t.text;
                  return t.name;
                }
                var APP_TOOLS = [{
                  name: 'cycle_theme',
                  title: 'Cycle theme',
                  description: 'Cycle the clock through its color themes',
                  inputSchema: { type: 'object', properties: {}, additionalProperties: false }
                }];

                // Handle requests the host sends INTO this app.
                window.addEventListener('message', function (event) {
                  var d = event.data;
                  if (!d || d.jsonrpc !== '2.0' || typeof d.method !== 'string' || d.id == null) return;
                  if (d.method === 'tools/list') {
                    reply(d.id, { tools: APP_TOOLS });
                  } else if (d.method === 'tools/call') {
                    var name = d.params && d.params.name;
                    if (name === 'cycle_theme') {
                      themeIdx += 1;
                      var theme = applyTheme(themeIdx);
                      document.getElementById('result').textContent =
                        'Theme -> ' + theme + ' (set by host)';
                      reply(d.id, { content: [{ type: 'text', text: 'Theme set to ' + theme }] });
                    } else {
                      replyErr(d.id, -32602, 'Unknown app tool: ' + name);
                    }
                  }
                });

                // Handshake: announce the app + its tools capability, then init.
                sendRequest('ui/initialize', { appCapabilities: { tools: {} } })
                  .then(() => notify('ui/notifications/initialized', {}))
                  .catch(() => {});

                // Call a server tool through the host and show the result.
                document.getElementById('fetch').addEventListener('click', async () => {
                  const out = document.getElementById('result');
                  out.textContent = 'Calling atmosphere_version…';
                  try {
                    const res = await sendRequest('tools/call',
                      { name: 'atmosphere_version', arguments: {} });
                    const v = res && res.structuredContent ? res.structuredContent.version
                      : (res.content && res.content[0] ? res.content[0].text : '?');
                    out.textContent = 'Server version: ' + v;
                  } catch (e) {
                    out.textContent = 'Error: ' + e.message;
                  }
                });
              </script>
            </body>
            </html>
            """;

    // ── Prompts ──────────────────────────────────────────────────────────

    @McpPrompt(name = "chat_summary", description = "Summarize current chat status")
    public List<McpMessage> chatSummary() {
        var broadcaster = chatBroadcaster();
        var userCount = broadcaster != null ? broadcaster.getAtmosphereResources().size() : 0;
        return List.of(
                McpMessage.system(CHAT_SUMMARY_SYSTEM_PROMPT),
                McpMessage.user("There are currently " + userCount + " users connected to the chat. "
                        + "Summarize the chat status and suggest moderation actions if needed.")
        );
    }

    @McpPrompt(name = "analyze_topic", description = "Get an AI analysis of a topic")
    public List<McpMessage> analyzeTopic(
            @McpParam(name = "topic", description = "Topic to analyze") String topic,
            @McpParam(name = "depth", description = "Analysis depth: brief, moderate, or deep", required = false) String depth
    ) {
        var depthStr = depth != null ? depth : "moderate";
        return List.of(
                McpMessage.system(ANALYZE_TOPIC_SYSTEM_PROMPT + " Provide a " + depthStr + " analysis."),
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

}
