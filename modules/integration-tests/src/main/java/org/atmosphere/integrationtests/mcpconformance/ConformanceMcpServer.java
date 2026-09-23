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
package org.atmosphere.integrationtests.mcpconformance;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.mcp.annotation.McpComplete;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.runtime.McpServerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The fixture the official MCP conformance suite
 * ({@code @modelcontextprotocol/conformance}, server mode) runs against. Tool,
 * resource and prompt names and payloads follow the suite's reference
 * "everything server" so each scenario finds what it looks for. Only surface
 * the Atmosphere MCP API can express is implemented; scenarios needing more
 * (image/audio/binary content, progress, logging, ...) are listed with their
 * reason in {@code mcp-conformance/expected-failures-*.yml}.
 */
@Agent(name = "mcp-conformance", version = "1.0.0", endpoint = "/mcp", headless = true)
public class ConformanceMcpServer {

    private static final String TEST_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    // ── Tools ────────────────────────────────────────────────────────────

    @McpTool(name = "test_simple_text", description = "Returns simple text content")
    public String simpleText() {
        return "This is a simple text response for testing.";
    }

    @McpTool(name = "test_error_handling", description = "Always fails with an error result")
    public String errorHandling() {
        throw new IllegalStateException("This tool intentionally returns an error for testing");
    }

    @McpTool(name = "test_sampling", description = "Tests server-initiated sampling (LLM completion request)")
    public String sampling(@McpParam(name = "prompt", description = "The prompt to send to the LLM") String prompt,
                           McpServerContext context) {
        try {
            var result = context.sample(List.of(Map.of("role", "user",
                            "content", Map.of("type", "text", "text", prompt))),
                    Map.of("maxTokens", 100)).get(30, TimeUnit.SECONDS);
            var text = result.path("content").path("text").asString("No response");
            return "LLM response: " + text;
        } catch (Exception e) {
            return "Sampling not supported or error: " + e.getMessage();
        }
    }

    @McpTool(name = "test_elicitation", description = "Tests server-initiated elicitation (user input request)")
    public String elicitation(@McpParam(name = "message", description = "The message to show the user") String message,
                              McpServerContext context) {
        try {
            var result = context.elicit(message, Map.of(
                    "type", "object",
                    "properties", Map.of("response", Map.of("type", "string",
                            "description", "User's response")),
                    "required", List.of("response"))).get(30, TimeUnit.SECONDS);
            return "User response: action=" + result.path("action").asString("")
                    + ", content=" + result.path("content");
        } catch (Exception e) {
            return "Elicitation not supported or error: " + e.getMessage();
        }
    }

    // ── Resources ────────────────────────────────────────────────────────

    @McpResource(uri = "test://static-text", name = "static-text", title = "Static Text Resource",
            description = "A static text resource for testing", mimeType = "text/plain")
    public String staticText() {
        return "This is the content of the static text resource.";
    }

    @McpResource(uri = "test://template/{id}/data", name = "template", title = "Resource Template",
            description = "A resource template with parameter substitution", mimeType = "application/json")
    public Map<String, Object> template(@McpParam(name = "id") String id) {
        return Map.of("id", id, "templateTest", true, "data", "Data for ID: " + id);
    }

    @McpResource(uri = "test://watched-resource", name = "watched-resource", title = "Watched Resource",
            description = "A subscribable resource", mimeType = "text/plain")
    public String watched() {
        return "Watched resource content";
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @McpPrompt(name = "test_simple_prompt", description = "A simple prompt without arguments")
    public List<Map<String, Object>> simplePrompt() {
        return List.of(userText("This is a simple prompt for testing."));
    }

    @McpPrompt(name = "test_prompt_with_arguments", description = "A prompt with required arguments")
    public List<Map<String, Object>> promptWithArguments(
            @McpParam(name = "arg1", description = "First test argument") String arg1,
            @McpParam(name = "arg2", description = "Second test argument") String arg2) {
        return List.of(userText("Prompt with arguments: arg1='" + arg1 + "', arg2='" + arg2 + "'"));
    }

    @McpComplete(prompt = "test_prompt_with_arguments", argument = "arg1")
    public List<String> completeArg1(String prefix) {
        var out = new ArrayList<String>();
        for (var v : List.of("paris", "park", "party", "test", "testing")) {
            if (v.startsWith(prefix)) {
                out.add(v);
            }
        }
        return out;
    }

    @McpPrompt(name = "test_prompt_with_embedded_resource", description = "A prompt that includes an embedded resource")
    public List<Map<String, Object>> promptWithEmbeddedResource(
            @McpParam(name = "resourceUri", description = "URI of the resource to embed") String resourceUri) {
        return List.of(
                Map.of("role", "user", "content", Map.of("type", "resource",
                        "resource", Map.of("uri", resourceUri, "mimeType", "text/plain",
                                "text", "Embedded resource content for testing."))),
                userText("Please process the embedded resource above."));
    }

    @McpPrompt(name = "test_prompt_with_image", description = "A prompt that includes image content")
    public List<Map<String, Object>> promptWithImage() {
        return List.of(
                Map.of("role", "user", "content", Map.of("type", "image",
                        "data", TEST_IMAGE_BASE64, "mimeType", "image/png")),
                userText("Please analyze the image above."));
    }

    private static Map<String, Object> userText(String text) {
        return Map.of("role", "user", "content", Map.of("type", "text", "text", text));
    }
}
