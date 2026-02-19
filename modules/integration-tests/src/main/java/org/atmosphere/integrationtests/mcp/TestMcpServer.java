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
package org.atmosphere.integrationtests.mcp;

import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.annotation.McpTool;

/**
 * Simple MCP server for integration testing. Exposes a single tool
 * that echoes back its input.
 */
@McpServer(name = "test-server", version = "1.0.0", path = "/mcp")
public class TestMcpServer {

    @McpTool(name = "echo", description = "Echoes back the input text")
    public String echo(@McpParam(name = "text", description = "Text to echo") String text) {
        return "Echo: " + text;
    }

    @McpTool(name = "add", description = "Adds two numbers")
    public int add(
            @McpParam(name = "a", description = "First number") int a,
            @McpParam(name = "b", description = "Second number") int b) {
        return a + b;
    }
}
