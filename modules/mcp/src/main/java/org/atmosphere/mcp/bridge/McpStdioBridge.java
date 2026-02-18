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
package org.atmosphere.mcp.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * stdio-to-HTTP bridge for MCP. Reads JSON-RPC messages from stdin (one per line),
 * POSTs them to a Streamable HTTP MCP endpoint, and writes responses to stdout.
 *
 * <p>Usage:
 * <pre>
 *   java -jar atmosphere-mcp-stdio-bridge.jar http://localhost:8083/atmosphere/mcp
 * </pre>
 *
 * <p>Claude Desktop / VS Code configuration:
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "atmosphere": {
 *       "command": "java",
 *       "args": ["-jar", "atmosphere-mcp-stdio-bridge.jar", "http://localhost:8083/atmosphere/mcp"]
 *     }
 *   }
 * }
 * }</pre>
 */
public final class McpStdioBridge {

    private final String endpointUrl;
    private volatile String sessionId;

    public McpStdioBridge(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    /**
     * Main entry point. Reads from stdin, forwards to HTTP, writes to stdout.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java -jar atmosphere-mcp-stdio-bridge.jar <endpoint-url>");
            System.err.println("Example: java -jar atmosphere-mcp-stdio-bridge.jar http://localhost:8083/atmosphere/mcp");
            System.exit(1);
        }

        var bridge = new McpStdioBridge(args[0]);
        bridge.run();
    }

    /**
     * Run the bridge loop: read stdin lines, forward to HTTP, write responses to stdout.
     */
    public void run() throws IOException {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            var response = sendRequest(line);
            if (response != null && !response.isEmpty()) {
                System.out.println(response);
                System.out.flush();
            }
        }
    }

    /**
     * Send a JSON-RPC request to the MCP endpoint via HTTP POST.
     * Returns the response body, or null for notifications (202).
     */
    String sendRequest(String jsonRpcMessage) throws IOException {
        var url = URI.create(endpointUrl).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (sessionId != null) {
                conn.setRequestProperty("Mcp-Session-Id", sessionId);
            }

            // Write request body
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonRpcMessage.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            // Capture session ID from response
            var respSessionId = conn.getHeaderField("Mcp-Session-Id");
            if (respSessionId != null) {
                this.sessionId = respSessionId;
            }

            if (status == 202) {
                // Notification accepted, no response body
                return null;
            }

            var contentType = conn.getContentType();
            var body = readBody(conn);

            if (contentType != null && contentType.contains("text/event-stream")) {
                return parseSseData(body);
            }

            return body;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Terminate the MCP session by sending DELETE.
     */
    public void terminate() throws IOException {
        if (sessionId == null) {
            return;
        }
        var url = URI.create(endpointUrl).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parse SSE response — extract JSON from "data:" lines.
     */
    public static String parseSseData(String sseBody) {
        var sb = new StringBuilder();
        for (var line : sseBody.split("\n")) {
            if (line.startsWith("data: ")) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(line.substring(6));
            } else if (line.startsWith("data:")) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(line.substring(5));
            }
        }
        return sb.toString();
    }
}
