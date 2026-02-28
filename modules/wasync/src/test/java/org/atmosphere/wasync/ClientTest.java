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

import org.atmosphere.wasync.impl.DefaultOptionsBuilder;
import org.atmosphere.wasync.impl.DefaultRequestBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Client, RequestBuilder, and OptionsBuilder.
 */
class ClientTest {

    @Test
    void clientCreateReturnsSocket() {
        var client = Client.newClient();
        var socket = client.create();
        assertNotNull(socket);
        assertEquals(Socket.STATUS.INIT, socket.status());
    }

    @Test
    void requestBuilderDefaultTransports() {
        var builder = new DefaultRequestBuilder();
        var request = builder.uri("ws://localhost:8080/chat").build();

        assertEquals("ws://localhost:8080/chat", request.uri());
        assertEquals(Request.METHOD.GET, request.method());
        assertEquals(4, request.transport().size());
        assertEquals(Request.TRANSPORT.WEBSOCKET, request.transport().get(0));
        assertEquals(Request.TRANSPORT.SSE, request.transport().get(1));
        assertEquals(Request.TRANSPORT.STREAMING, request.transport().get(2));
        assertEquals(Request.TRANSPORT.LONG_POLLING, request.transport().get(3));
    }

    @Test
    void requestBuilderCustomTransport() {
        var builder = new DefaultRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .build();

        assertEquals(1, request.transport().size());
        assertEquals(Request.TRANSPORT.WEBSOCKET, request.transport().get(0));
    }

    @Test
    void requestBuilderHeaders() {
        var builder = new DefaultRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .header("Authorization", "Bearer token123")
                .header("X-Custom", "value1")
                .header("X-Custom", "value2")
                .build();

        assertEquals(List.of("Bearer token123"), request.headers().get("Authorization"));
        assertEquals(List.of("value1", "value2"), request.headers().get("X-Custom"));
    }

    @Test
    void requestBuilderQueryString() {
        var builder = new DefaultRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .queryString("room", "general")
                .queryString("user", "alice")
                .build();

        assertEquals(List.of("general"), request.queryString().get("room"));
        assertEquals(List.of("alice"), request.queryString().get("user"));
    }

    @Test
    void requestBuilderEncodersDecoders() {
        Encoder<String, String> encoder = s -> s.toUpperCase();
        Decoder<String, String> decoder = (e, s) -> s.toLowerCase();

        var builder = new DefaultRequestBuilder();
        var request = builder
                .uri("ws://localhost:8080/chat")
                .encoder(encoder)
                .decoder(decoder)
                .build();

        assertEquals(1, request.encoders().size());
        assertEquals(1, request.decoders().size());
    }

    @Test
    void optionsBuilderDefaults() {
        var options = new DefaultOptionsBuilder().build();

        assertTrue(options.reconnect());
        assertEquals(1000, options.reconnectTimeoutInMilliseconds());
        assertEquals(-1, options.reconnectAttempts());
        assertEquals(2000, options.waitBeforeUnlocking());
        assertEquals(300, options.requestTimeoutInSeconds());
        assertFalse(options.binary());
    }

    @Test
    void optionsBuilderCustomValues() {
        var options = new DefaultOptionsBuilder()
                .reconnect(false)
                .reconnectAttempts(5)
                .pauseBeforeReconnectInSeconds(3)
                .requestTimeoutInSeconds(60)
                .binary(true)
                .build();

        assertFalse(options.reconnect());
        assertEquals(5, options.reconnectAttempts());
        assertEquals(3000, options.reconnectTimeoutInMilliseconds());
        assertEquals(60, options.requestTimeoutInSeconds());
        assertTrue(options.binary());
    }

    @Test
    void socketRegistersCallbacks() {
        var client = Client.newClient();
        var messages = new ArrayList<Object>();

        var socket = client.create();
        socket.on(Event.MESSAGE, messages::add)
              .on(Event.OPEN, messages::add)
              .on("custom", messages::add);

        assertEquals(Socket.STATUS.INIT, socket.status());
    }

    @Test
    void functionResolverDefault() {
        assertTrue(FunctionResolver.DEFAULT.resolve("MESSAGE", Event.MESSAGE, "hello"));
        assertTrue(FunctionResolver.DEFAULT.resolve("OPEN", Event.OPEN, null));
        assertFalse(FunctionResolver.DEFAULT.resolve("MESSAGE", Event.OPEN, "hello"));
    }

    @Test
    void decodedRecord() {
        var decoded = Decoder.Decoded.of("hello");
        assertEquals("hello", decoded.decoded());
        assertEquals(Decoder.Decoded.Action.CONTINUE, decoded.action());
    }

    @Test
    void functionBindingRecord() {
        Function<String> fn = s -> {};
        var binding = new FunctionBinding("MESSAGE", fn);
        assertEquals("MESSAGE", binding.functionName());
        assertEquals(fn, binding.function());
    }
}
