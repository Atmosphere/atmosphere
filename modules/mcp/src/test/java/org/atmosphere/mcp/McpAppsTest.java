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

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpProtocolHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MCP Apps server-side support (SEP-1865): a {@code @McpTool(uiResource)}
 * declares {@code _meta.ui.resourceUri} in {@code tools/list}, the server
 * advertises the {@code io.modelcontextprotocol/apps} extension, and the
 * {@code ui://} resource is served as {@code text/html;profile=mcp-app}.
 */
public class McpAppsTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String APP_URI = "ui://clock/app.html";

    private McpProtocolHandler handler;
    private AtmosphereResource resource;

    public static class AppServer {
        @McpTool(name = "clock_app", description = "Interactive clock", uiResource = APP_URI)
        public String clock() {
            return "rendered in the app UI";
        }

        @McpResource(uri = APP_URI, name = "Clock UI", description = "Clock app HTML",
                mimeType = "text/html;profile=mcp-app")
        public String clockHtml() {
            return "<!doctype html><html><body><h1 id=\"clock\">tick</h1></body></html>";
        }
    }

    public static class PlainServer {
        @McpTool(name = "greet", description = "Greet")
        public String greet() {
            return "hi";
        }
    }

    @BeforeEach
    public void setUp() {
        var registry = new McpRegistry();
        registry.scan(new AppServer());
        handler = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("apps-uuid");
    }

    private static String meta() {
        return """
                "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"c","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{}
                }""";
    }

    @Test
    public void testToolDeclaresUiResource() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + meta() + "}}";
        var tools = mapper.readTree(handler.handleMessage(resource, req)).get("result").get("tools");
        var clock = tools.get(0);
        assertEquals("clock_app", clock.get("name").stringValue());
        assertEquals(APP_URI, clock.get("_meta").get("ui").get("resourceUri").stringValue(),
                "SEP-1865: the tool advertises its ui:// resource in _meta.ui.resourceUri");
    }

    @Test
    public void testDiscoverAdvertisesAppsExtension() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + meta() + "}}";
        var caps = mapper.readTree(handler.handleMessage(resource, req)).get("result").get("capabilities");
        var apps = caps.get("extensions").get("io.modelcontextprotocol/apps");
        assertNotNull(apps, "server with an app tool advertises the apps extension");
        assertEquals("text/html;profile=mcp-app", apps.get("mimeTypes").get(0).stringValue());
    }

    @Test
    public void testUiResourceServesHtml() throws Exception {
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{"
                + "\"uri\":\"" + APP_URI + "\"," + meta() + "}}";
        var contents = mapper.readTree(handler.handleMessage(resource, req)).get("result").get("contents");
        assertEquals("text/html;profile=mcp-app", contents.get(0).get("mimeType").stringValue());
        assertTrue(contents.get(0).get("text").stringValue().contains("id=\"clock\""),
                "the ui:// resource serves the app HTML");
    }

    @Test
    public void testNoAppsExtensionWhenNoAppTool() throws Exception {
        var registry = new McpRegistry();
        registry.scan(new PlainServer());
        var plain = new McpProtocolHandler("test-server", "1.0.0", registry, mock(AtmosphereConfig.class));
        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + meta() + "}}";
        var caps = mapper.readTree(plain.handleMessage(resource, req)).get("result").get("capabilities");
        // No app tool → no apps extension advertised (Runtime Truth). No extensions at all here.
        assertNull(caps.get("extensions"));
    }
}
