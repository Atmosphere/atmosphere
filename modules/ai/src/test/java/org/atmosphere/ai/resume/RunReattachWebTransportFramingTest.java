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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A page reloaded mid-answer over WebTransport reattaches to its run and is
 * written the run's frames by the reattach, not by a broadcast. WebTransport
 * frames text by a newline it appends to each write, and the client splits the
 * stream on it before it reads the {@code length|message} prefix
 * {@link TrackMessageSizeInterceptor} puts in front of each write. The reattach
 * wrote each frame with a newline of its own, which the prefix counted: the
 * client, the newline stripped, read every frame one character short, took the
 * missing one from the next frame's prefix, and failed on a length that was not
 * a number, then reconnected to the same replay, endlessly. A replayed frame is
 * written as the live path writes it: the JSON alone.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg constructor.
@SuppressWarnings("deprecation")
class RunReattachWebTransportFramingTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeEach
    void setUp() {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aWebTransportClientParsesEveryFrameAReattachReplaysAndForwards() throws Exception {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var handle = registry.register("/ai-chat", "anonymous", "original", execution);
        var buffer = handle.replayBuffer();
        buffer.capture("streaming-text", "The");
        buffer.capture("streaming-text", " history");

        var wire = new StringBuilder();
        var resource = webTransportResource(handle.runId(), wire);
        assertEquals(2, RunReattachSupport.replayPendingRun(resource, registry), "the backlog is replayed");
        // The run goes on: the reattach forwards the rest as it comes.
        buffer.capture("streaming-text", " of canals");
        buffer.capture("complete", "");
        execution.complete();

        var messages = clientMessages(wire.toString());
        assertEquals(4, messages.size(), "one message per frame: " + messages + " from " + wire);
        assertTrue(messages.get(0).startsWith("{") && messages.get(0).contains("\"data\":\"The\"")
                && messages.get(0).endsWith("}"), messages.get(0));
        assertTrue(messages.get(1).contains("\"data\":\" history\"") && messages.get(1).endsWith("}"),
                messages.get(1));
        assertTrue(messages.get(2).contains("\"data\":\" of canals\"") && messages.get(2).endsWith("}"),
                messages.get(2));
        assertTrue(messages.get(3).contains("\"type\":\"complete\"") && messages.get(3).endsWith("}"),
                messages.get(3));
    }

    /**
     * A page reloaded after its run ended, while the run is still replayable,
     * is written the whole run at once: each frame must still reach the client
     * as one message. Written as one newline-joined batch it measured as one
     * message the client, splitting on the newlines, never completed, until a
     * later message supplied the missing characters and ran into one bubble.
     */
    @Test
    void aWebTransportClientParsesEveryFrameOfARunThatAlreadyEnded() throws Exception {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var handle = registry.register("/ai-chat", "anonymous", "original", execution);
        var buffer = handle.replayBuffer();
        buffer.capture("streaming-text", "The");
        buffer.capture("streaming-text", " history");
        buffer.capture("complete", "");
        execution.complete();

        var wire = new StringBuilder();
        assertEquals(3, RunReattachSupport.replayPendingRun(webTransportResource(handle.runId(), wire), registry),
                "the ended run is replayed whole");

        var messages = clientMessages(wire.toString());
        assertEquals(3, messages.size(), "one message per frame: " + messages + " from " + wire);
        assertTrue(messages.get(0).contains("\"data\":\"The\"") && messages.get(0).endsWith("}"), messages.get(0));
        assertTrue(messages.get(1).contains("\"data\":\" history\"") && messages.get(1).endsWith("}"),
                messages.get(1));
        assertTrue(messages.get(2).contains("\"type\":\"complete\"") && messages.get(2).endsWith("}"),
                messages.get(2));
    }

    /**
     * A WebTransport connection with the run id, its writes going through
     * {@link TrackMessageSizeInterceptor} as the Console's subscription asks
     * ({@code X-Atmosphere-TrackMessageSize}) to the WebSocket the WebTransport
     * session is bridged to, which frames each text write with a newline as the
     * Reactor Netty WebTransport session does.
     */
    private AtmosphereResource webTransportResource(String runId, StringBuilder wire) throws Exception {
        var headers = new HashMap<>(Map.of(
                HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE, "true",
                HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.WEBSOCKET_TRANSPORT));
        var request = new AtmosphereRequestImpl.Builder().headers(headers).build();
        request.setAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE, runId);
        var bridge = new NewlineFramedBridge(config, wire);
        var response = AtmosphereResponseImpl.newInstance(config, request, bridge);
        response.delegateToNativeResponse(false);
        var resource = new AtmosphereResourceImpl(config, factory.lookup("/ai-chat", true), request, response,
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        bridge.resource(resource);
        // As the framework does for every request it dispatches.
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
        var trackMessageSize = new TrackMessageSizeInterceptor();
        trackMessageSize.configure(config);
        trackMessageSize.inspect(resource);
        assertEquals(AtmosphereResource.TRANSPORT.WEBSOCKET, resource.transport(),
                "a WebTransport connection is a WebSocket-transport resource");
        return resource;
    }

    /**
     * What the atmosphere.js WebTransport transport makes of the bytes: split on
     * the newline ({@code drainFrames}), then each piece read as
     * {@code length|message} with a partial message carried over to the next
     * piece ({@code AtmosphereProtocol.processMessage} with
     * {@code trackMessageLength}), failing on a length that is not a number.
     */
    private static List<String> clientMessages(String wire) {
        var messages = new ArrayList<String>();
        var partial = "";
        for (var piece : wire.split("\n", -1)) {
            if (piece.isEmpty()) {
                continue;
            }
            var data = partial + piece;
            var delimiter = data.indexOf('|');
            if (delimiter < 0) {
                messages.add(piece);
                continue;
            }
            while (delimiter >= 0) {
                var lengthText = data.substring(0, delimiter);
                int length;
                try {
                    length = Integer.parseInt(lengthText);
                } catch (NumberFormatException e) {
                    throw new AssertionError("Message length \"" + lengthText + "\" is not a number; wire: " + wire);
                }
                if (delimiter + 1 + length > data.length()) {
                    break;
                }
                messages.add(data.substring(delimiter + 1, delimiter + 1 + length));
                data = data.substring(delimiter + 1 + length);
                delimiter = data.indexOf('|');
            }
            partial = data;
        }
        assertTrue(partial.isEmpty(), "no frame is left half read: \"" + partial + "\" from " + wire);
        return messages;
    }

    /**
     * The WebSocket a WebTransport session is bridged to, framing each text
     * write with a trailing newline as the session it forwards to does.
     */
    private static final class NewlineFramedBridge extends WebSocket {

        private final StringBuilder wire;

        NewlineFramedBridge(AtmosphereConfig config, StringBuilder wire) {
            super(config);
            this.wire = wire;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) {
            synchronized (wire) {
                wire.append(s).append('\n');
            }
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) {
            return write(new String(b, offset, length, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
        }
    }
}
