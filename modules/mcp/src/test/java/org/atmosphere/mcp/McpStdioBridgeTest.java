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
package org.atmosphere.mcp;

import org.atmosphere.mcp.bridge.McpStdioBridge;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for the stdio-to-HTTP MCP bridge.
 */
public class McpStdioBridgeTest {

    @Test
    public void testParseSseDataSingleEvent() {
        var sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals(result, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
    }

    @Test
    public void testParseSseDataMultipleEvents() {
        var sse = "event: message\ndata: {\"first\":true}\n\nevent: message\ndata: {\"second\":true}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals(result, "{\"first\":true}\n{\"second\":true}");
    }

    @Test
    public void testParseSseDataNoSpace() {
        var sse = "data:{\"compact\":true}\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals(result, "{\"compact\":true}");
    }

    @Test
    public void testParseSseDataEmpty() {
        var result = McpStdioBridge.parseSseData("");
        assertEquals(result, "");
    }

    @Test
    public void testParseSseDataIgnoresNonDataLines() {
        var sse = "event: message\nid: 42\ndata: {\"result\":\"ok\"}\nretry: 1000\n\n";
        var result = McpStdioBridge.parseSseData(sse);
        assertEquals(result, "{\"result\":\"ok\"}");
    }

    @Test
    public void testBridgeConstructor() {
        var bridge = new McpStdioBridge("http://localhost:8083/mcp");
        assertNotNull(bridge);
    }
}
