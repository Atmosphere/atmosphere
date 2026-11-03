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
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.runtime.McpServerContext;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Simple MCP server for integration testing: an echo and an add tool, a tool
 * that asks the client for input through {@code elicitation/create}, and one
 * that takes as long as it is told to.
 */
@Agent(name = "test-server", version = "1.0.0", endpoint = "/mcp", headless = true)
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

    @McpTool(name = "confirm", description = "Asks the client to confirm through elicitation")
    public String confirm(@McpParam(name = "question", description = "What to ask") String question,
                          McpServerContext context) throws Exception {
        var answer = context.elicit(question, Map.of(
                "type", "object",
                "properties", Map.of("ok", Map.of("type", "boolean"))))
                .get(30, TimeUnit.SECONDS);
        // The future completes with the client's whole JSON-RPC response.
        var result = answer.path("result");
        return "action=" + result.path("action").asString("") + " ok=" + result.path("content").path("ok");
    }

    @McpTool(name = "slow", description = "Answers after the given number of milliseconds")
    public String slow(@McpParam(name = "millis", description = "How long to take") int millis)
            throws InterruptedException {
        Thread.sleep(millis);
        return "slept " + millis;
    }
}
