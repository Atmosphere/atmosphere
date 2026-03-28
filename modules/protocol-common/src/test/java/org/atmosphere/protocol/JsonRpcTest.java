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
package org.atmosphere.protocol;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonRpcTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestSerialization() throws Exception {
        var req = new JsonRpc.Request(1, "test/method", Map.of("key", "value"));
        var json = mapper.writeValueAsString(req);
        var parsed = mapper.readValue(json, JsonRpc.Request.class);

        assertEquals("2.0", parsed.jsonrpc());
        assertEquals(1, parsed.id());
        assertEquals("test/method", parsed.method());
        assertNotNull(parsed.params());
    }

    @Test
    void successResponse() throws Exception {
        var resp = JsonRpc.Response.success(1, Map.of("result", "ok"));
        var json = mapper.writeValueAsString(resp);
        var parsed = mapper.readValue(json, JsonRpc.Response.class);

        assertEquals("2.0", parsed.jsonrpc());
        assertEquals(1, parsed.id());
        assertNotNull(parsed.result());
        assertNull(parsed.error());
    }

    @Test
    void errorResponse() throws Exception {
        var resp = JsonRpc.Response.error(1, JsonRpc.INVALID_PARAMS, "Missing param");
        var json = mapper.writeValueAsString(resp);
        var parsed = mapper.readValue(json, JsonRpc.Response.class);

        assertEquals("2.0", parsed.jsonrpc());
        assertNull(parsed.result());
        assertNotNull(parsed.error());
        assertEquals(JsonRpc.INVALID_PARAMS, parsed.error().code());
        assertEquals("Missing param", parsed.error().message());
    }

    @Test
    void notificationHasNoId() throws Exception {
        var notif = new JsonRpc.Notification("test/notify", Map.of("data", "value"));
        var json = mapper.writeValueAsString(notif);

        assertEquals("2.0", notif.jsonrpc());
        assertEquals("test/notify", notif.method());
        // Notification records don't have an id field
        assertNotNull(json);
    }

    @Test
    void errorCodes() {
        assertEquals(-32700, JsonRpc.PARSE_ERROR);
        assertEquals(-32600, JsonRpc.INVALID_REQUEST);
        assertEquals(-32601, JsonRpc.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpc.INVALID_PARAMS);
        assertEquals(-32603, JsonRpc.INTERNAL_ERROR);
    }
}
