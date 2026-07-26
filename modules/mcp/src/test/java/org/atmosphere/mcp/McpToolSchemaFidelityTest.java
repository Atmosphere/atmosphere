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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the structural fidelity of the {@code inputSchema} the MCP server
 * publishes on {@code tools/list}. Previously every parameter was emitted as a
 * bare {@code type}/{@code description} pair — a Java enum argument reached
 * clients as an unconstrained {@code "string"} and a list argument as
 * {@code "string"} with no {@code items} at all.
 */
class McpToolSchemaFidelityTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    enum Priority { LOW, HIGH }

    record Assignee(String name, String email) { }

    @SuppressWarnings("unused")
    public static class Tools {
        @McpTool(name = "create_task", description = "Create a task")
        public String create(@McpParam(name = "title") String title,
                             @McpParam(name = "priority") Priority priority,
                             @McpParam(name = "labels") List<String> labels,
                             @McpParam(name = "assignee") Assignee assignee) {
            return "ok";
        }
    }

    private static Map<String, Object> schema() {
        var registry = new McpRegistry();
        registry.scan(new Tools());
        return McpRegistry.inputSchema(registry.tool("create_task").orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(String name) {
        var properties = (Map<String, Object>) schema().get("properties");
        return (Map<String, Object>) properties.get(name);
    }

    @Test
    void enumParameterPublishesItsValueSet() {
        var priority = property("priority");
        assertEquals("string", priority.get("type"));
        assertEquals(List.of("LOW", "HIGH"), priority.get("enum"),
                "an enum argument must publish its permitted values");
    }

    @Test
    void listParameterPublishesItsItemType() {
        var labels = property("labels");
        assertEquals("array", labels.get("type"));
        assertEquals(Map.of("type", "string"), labels.get("items"));
    }

    @Test
    void recordParameterPublishesItsProperties() {
        var assignee = property("assignee");
        assertEquals("object", assignee.get("type"));
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) assignee.get("properties");
        assertTrue(nested.containsKey("name"), nested.toString());
        assertTrue(nested.containsKey("email"), nested.toString());
    }

    @Test
    void scalarParameterKeepsTheHistoricalFlatShape() {
        var title = property("title");
        assertEquals("string", title.get("type"));
        assertFalse(title.containsKey("enum"));
        assertFalse(title.containsKey("items"));
        assertFalse(title.containsKey("properties"));
    }

    @Test
    void toolsListCarriesTheStructureOverTheWire() throws Exception {
        var registry = new McpRegistry();
        registry.scan(new Tools());
        var handler = new McpProtocolHandler("test", "1.0", registry,
                mock(AtmosphereConfig.class));

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("schema-test");
        when(request.getAttribute(anyString())).thenReturn(null);
        org.mockito.Mockito.doNothing().when(request).setAttribute(anyString(), any());

        var response = handler.handleMessage(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var props = mapper.readTree(response)
                .get("result").get("tools").get(0).get("inputSchema").get("properties");

        assertEquals("LOW", props.get("priority").get("enum").get(0).stringValue(),
                "tools/list must expose the enum value set on the wire");
        assertEquals("string", props.get("labels").get("items").get("type").stringValue());
        assertTrue(props.get("assignee").get("properties").has("email"));
    }
}
