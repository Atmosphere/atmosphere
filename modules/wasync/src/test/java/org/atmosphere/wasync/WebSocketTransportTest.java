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
package org.atmosphere.wasync;

import org.atmosphere.wasync.transport.WebSocketTransport;
import org.atmosphere.wasync.impl.DefaultOptionsBuilder;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for WebSocketTransport.
 */
class WebSocketTransportTest {

    @Test
    void transportReportsCorrectName() {
        var options = new DefaultOptionsBuilder().build();
        try (var client = HttpClient.newHttpClient()) {
            var transport = new WebSocketTransport(client, options);
            assertEquals(Request.TRANSPORT.WEBSOCKET, transport.name());
            assertEquals(Socket.STATUS.INIT, transport.status());
        }
    }

    @Test
    void transportRegistersFunction() {
        var options = new DefaultOptionsBuilder().build();
        try (var client = HttpClient.newHttpClient()) {
            var transport = new WebSocketTransport(client, options);
            Function<String> fn = s -> {};
            var result = transport.registerFunction(new FunctionBinding(Event.MESSAGE.name(), fn));
            assertNotNull(result);
        }
    }
}
