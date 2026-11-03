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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the stdio-to-HTTP MCP bridge.
 */
public class McpStdioBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testParseSseDataSingleEvent() {
        var sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", result);
    }

    @Test
    public void testParseSseDataMultipleEvents() {
        var sse = "event: message\ndata: {\"first\":true}\n\nevent: message\ndata: {\"second\":true}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals("{\"first\":true}\n{\"second\":true}", result);
    }

    @Test
    public void testParseSseDataNoSpace() {
        var sse = "data:{\"compact\":true}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals("{\"compact\":true}", result);
    }

    @Test
    public void testParseSseDataEmpty() {
        var result = McpStdioBridge.parseSseData("");
        assertEquals("", result);
    }

    @Test
    public void testParseSseDataIgnoresNonDataLines() {
        var sse = "event: message\nid: 42\ndata: {\"result\":\"ok\"}\nretry: 1000\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals("{\"result\":\"ok\"}", result);
    }

    @Test
    public void testParseSseDataJoinsMultiLineDataOfOneEvent() {
        var sse = "event: message\ndata: {\"a\":\ndata: 1}\n\n";
        assertEquals("{\"a\":\n1}", McpStdioBridge.parseSseData(sse));
    }

    @Test
    public void testOptionsReadEndpointArgumentTimeoutAndTokenFromEnvironment() {
        var options = McpStdioBridge.Options.parse(new String[] {"http://localhost:8083/mcp", "--timeout", "45"},
                Map.of(McpStdioBridge.ENV_TOKEN, "secret"));
        assertEquals("http://localhost:8083/mcp", options.endpoint().toString());
        assertEquals(Duration.ofSeconds(45), options.requestTimeout());
        assertEquals("secret", options.bearerToken());
        assertTrue(options.openStream());
    }

    @Test
    public void testOptionsFallBackToEnvironmentEndpointAndNoTimeout() {
        var options = McpStdioBridge.Options.parse(new String[0],
                Map.of(McpStdioBridge.ENV_URL, "https://mcp.example.com/mcp", McpStdioBridge.ENV_TIMEOUT, "0"));
        assertEquals("https://mcp.example.com/mcp", options.endpoint().toString());
        assertNull(options.requestTimeout(), "0 means wait as long as the call takes");
        assertNull(options.bearerToken());
    }

    @Test
    public void testOptionsRejectTokenOnCommandLineAndBadInput() {
        assertThrows(IllegalArgumentException.class, () -> McpStdioBridge.Options.parse(
                new String[] {"http://localhost/mcp", "--token", "leaks-into-ps"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> McpStdioBridge.Options.parse(new String[0], Map.of()));
        assertThrows(IllegalArgumentException.class, () -> McpStdioBridge.Options.parse(
                new String[] {"file:///etc/passwd"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> McpStdioBridge.Options.parse(
                new String[] {"http://localhost/mcp", "--timeout", "-1"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> McpStdioBridge.Options.parse(
                new String[] {"http://localhost/mcp"}, Map.of(McpStdioBridge.ENV_TOKEN, "a\r\nX-Injected: 1")));
    }

    @Test
    public void testErrorAnswersARequestWithItsId() {
        var request = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/call\"}");
        var error = McpStdioBridge.error(request, McpStdioBridge.TRANSPORT_ERROR, "boom", "body");
        assertEquals("abc", error.get("id").asString());
        assertEquals(McpStdioBridge.TRANSPORT_ERROR, error.path("error").path("code").asInt());
        assertEquals("body", error.path("error").path("data").path("body").asString());
    }

    @Test
    public void testErrorIsNotSentForANotificationOrAResponse() {
        var notification = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        var response = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}");
        assertNull(McpStdioBridge.error(notification, -1, "x", null));
        assertNull(McpStdioBridge.error(response, -1, "x", null));
    }

    @Test
    public void testErrorForABatchAnswersEachRequestInIt() {
        var batch = MAPPER.readTree("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}]");
        var errors = McpStdioBridge.error(batch, -1, "x", null);
        assertTrue(errors.isArray());
        assertEquals(1, errors.size());
        assertEquals(1, errors.get(0).get("id").asInt());
    }
}
