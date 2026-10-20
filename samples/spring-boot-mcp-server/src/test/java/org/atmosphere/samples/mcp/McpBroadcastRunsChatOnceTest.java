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
package org.atmosphere.samples.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code broadcast_message} MCP tool sends the chat's JSON to every
 * subscriber. It must reach each of them as is, not run {@link Chat}'s
 * {@code @Message} method once per recipient: that logged
 * {@code "mcp just sent …"} as many times as there were subscribers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBroadcastRunsChatOnceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    private Socket subscribe(CountDownLatch received, String text) throws Exception {
        var client = AtmosphereClient.newClient();
        var opened = new CountDownLatch(1);
        var socket = client.create(client.newOptionsBuilder().reconnect(false).build());
        socket.on(Event.OPEN, (Function<Object>) o -> opened.countDown())
                .on(Event.MESSAGE, (Function<Object>) m -> {
                    if (m.toString().contains(text)) {
                        received.countDown();
                    }
                })
                .open(client.newRequestBuilder()
                        .uri("ws://localhost:" + port + "/atmosphere/chat")
                        .transport(Request.TRANSPORT.WEBSOCKET)
                        .enableProtocol(false)
                        .build());
        assertThat(opened.await(10, TimeUnit.SECONDS)).as("subscriber connects").isTrue();
        return socket;
    }

    @Test
    void anMcpBroadcastReachesEverySubscriberWithoutRunningTheChatPerRecipient() throws Exception {
        var chatLogger = (Logger) LoggerFactory.getLogger(Chat.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        chatLogger.addAppender(appender);
        var sockets = new ArrayList<Socket>();
        try {
            var received = new CountDownLatch(2);
            sockets.add(subscribe(received, "mcp-broadcast-once"));
            sockets.add(subscribe(received, "mcp-broadcast-once"));
            Thread.sleep(500);

            var tools = new DemoMcpServer();
            ReflectionTestUtils.setField(tools, "config", framework.getAtmosphereConfig());
            var result = tools.broadcastMessage("mcp-broadcast-once", "mcp");
            assertThat(result).containsEntry("status", "sent");

            assertThat(received.await(10, TimeUnit.SECONDS))
                    .as("both subscribers receive the MCP broadcast").isTrue();
            List<String> sent = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("mcp-broadcast-once"))
                    .toList();
            assertThat(sent).as("Chat's @Message must not run once per recipient").hasSizeLessThanOrEqualTo(1);
        } finally {
            chatLogger.detachAppender(appender);
            sockets.forEach(Socket::close);
        }
    }
}
