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

import org.atmosphere.mcp.protocol.JsonRpc;
import org.atmosphere.mcp.protocol.McpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonRpcTest {

    @Test
    void versionIs2_0() {
        assertEquals("2.0", JsonRpc.VERSION);
    }

    @Test
    void requestWithConvenienceConstructor() {
        var req = new JsonRpc.Request(1, "tools/list", null);
        assertEquals("2.0", req.jsonrpc());
        assertEquals(1, req.id());
        assertEquals("tools/list", req.method());
        assertNull(req.params());
    }

    @Test
    void requestWithFullConstructor() {
        var params = java.util.Map.of("name", "test");
        var req = new JsonRpc.Request("2.0", "42", "tools/call", params);
        assertEquals("42", req.id());
        assertEquals("tools/call", req.method());
        assertEquals(params, req.params());
    }

    @Test
    void responseSuccess() {
        var result = java.util.Map.of("status", "ok");
        var res = JsonRpc.Response.success(1, result);
        assertEquals("2.0", res.jsonrpc());
        assertEquals(1, res.id());
        assertEquals(result, res.result());
        assertNull(res.error());
    }

    @Test
    void responseErrorWithoutData() {
        var res = JsonRpc.Response.error(2, JsonRpc.PARSE_ERROR, "parse error");
        assertEquals("2.0", res.jsonrpc());
        assertEquals(2, res.id());
        assertNull(res.result());
        assertNotNull(res.error());
        assertEquals(JsonRpc.PARSE_ERROR, res.error().code());
        assertEquals("parse error", res.error().message());
        assertNull(res.error().data());
    }

    @Test
    void responseErrorWithData() {
        var data = "extra detail";
        var res = JsonRpc.Response.error(3, JsonRpc.INTERNAL_ERROR, "internal", data);
        assertEquals(JsonRpc.INTERNAL_ERROR, res.error().code());
        assertEquals("extra detail", res.error().data());
    }

    @Test
    void notificationConvenienceConstructor() {
        var params = java.util.Map.of("key", "value");
        var notif = new JsonRpc.Notification("notifications/progress", params);
        assertEquals("2.0", notif.jsonrpc());
        assertEquals("notifications/progress", notif.method());
        assertEquals(params, notif.params());
    }

    @Test
    void errorCodes() {
        assertEquals(-32700, JsonRpc.PARSE_ERROR);
        assertEquals(-32600, JsonRpc.INVALID_REQUEST);
        assertEquals(-32601, JsonRpc.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpc.INVALID_PARAMS);
        assertEquals(-32603, JsonRpc.INTERNAL_ERROR);
    }

    @Test
    void errorRecord() {
        var err = new JsonRpc.Error(-32600, "invalid", null);
        assertEquals(-32600, err.code());
        assertEquals("invalid", err.message());
        assertNull(err.data());
    }

    @Test
    void mcpMethodConstants() {
        assertEquals("initialize", McpMethod.INITIALIZE);
        assertEquals("notifications/initialized", McpMethod.INITIALIZED);
        assertEquals("ping", McpMethod.PING);
        assertEquals("tools/list", McpMethod.TOOLS_LIST);
        assertEquals("tools/call", McpMethod.TOOLS_CALL);
        assertEquals("resources/list", McpMethod.RESOURCES_LIST);
        assertEquals("resources/read", McpMethod.RESOURCES_READ);
        assertEquals("resources/subscribe", McpMethod.RESOURCES_SUBSCRIBE);
        assertEquals("resources/unsubscribe", McpMethod.RESOURCES_UNSUBSCRIBE);
        assertEquals("prompts/list", McpMethod.PROMPTS_LIST);
        assertEquals("prompts/get", McpMethod.PROMPTS_GET);
        assertEquals("notifications/progress", McpMethod.PROGRESS);
        assertEquals("notifications/cancelled", McpMethod.CANCELLED);
        assertEquals("notifications/resources/updated", McpMethod.RESOURCES_UPDATED);
    }
}
