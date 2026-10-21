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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.ai.websearch.WebSearchQuery;
import org.atmosphere.ai.websearch.WebSearchSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The research agent's {@code web_search} skill over the real A2A wire, as the
 * e2e spec ({@code multi-agent-startup-team.spec.ts}) calls it in keyless CI:
 * with no web search engine configured the search does not run, so the task
 * ends {@code TASK_STATE_FAILED} with the fail-closed text as its status
 * message and no artifact, not completed with that text as its result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class ResearchAgentA2aWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void aSearchThatDidNotRunFailsItsTaskOverA2a() throws Exception {
        var notRun = WebSearchSupport.shared().search(WebSearchQuery.of("AI developer tools", 2));
        assertFalse(notRun.available(), "precondition: the test JVM configures no web search engine");

        var body = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "SendMessage",
                "params", Map.of(
                        "message", Map.of(
                                "messageId", "msg-web-search",
                                "role", "ROLE_USER",
                                "parts", List.of(Map.of("text", "test")),
                                "metadata", Map.of("skillId", "web_search")),
                        "arguments", Map.of("query", "AI developer tools", "num_results", "2"))));
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/atmosphere/a2a/research"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        var result = mapper.readTree(response.body()).path("result");
        JsonNode task = result.has("task") ? result.path("task") : result;
        assertEquals("TASK_STATE_FAILED", task.path("status").path("state").asText(),
                "a search that did not run fails its task: " + response.body());
        assertEquals(notRun.toModelText(),
                task.path("status").path("message").path("parts").path(0).path("text").asText(),
                "the failed task carries the fail-closed text: " + response.body());
        assertTrue(task.path("artifacts").isMissingNode() || task.path("artifacts").isEmpty(),
                "a search that did not run adds no artifact: " + response.body());
    }
}
