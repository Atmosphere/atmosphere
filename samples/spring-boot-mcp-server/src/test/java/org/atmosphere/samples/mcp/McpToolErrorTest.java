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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool that cannot do what it was asked reports it as a tool error: the MCP
 * {@code CallToolResult} carries {@code isError: true}, so a client (or the
 * model driving it) can tell a failure from a result. The 2026-10-20 sweep had
 * {@code send_message} to an unknown user answer
 * {@code {"error":"User not found: does-not-exist"}} with {@code isError: false}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class McpToolErrorTest {

    @LocalServerPort
    int port;

    private JsonNode callTool(String name, String arguments) throws Exception {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + arguments + "}}";
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/atmosphere/mcp"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "tools/call must be answered: " + response.body());
            var result = JsonMapper.builder().build().readTree(response.body()).path("result");
            assertTrue(result.isObject(), "expected a CallToolResult: " + response.body());
            return result;
        }
    }

    @Test
    void sendMessageToAnUnknownUserIsAToolError() throws Exception {
        var result = callTool("send_message", "{\"uuid\":\"does-not-exist\",\"message\":\"hi\"}");
        assertTrue(result.path("isError").asBoolean(false), "an unknown user must be a tool error: " + result);
        assertEquals("User not found: does-not-exist",
                result.path("content").path(0).path("text").asString(), result.toString());
    }

    @Test
    void aSuccessfulCallIsNotAToolError() throws Exception {
        var result = callTool("atmosphere_version", "{}");
        assertTrue(result.has("isError") && !result.path("isError").asBoolean(true),
                "a successful call reports isError false: " + result);
    }
}
