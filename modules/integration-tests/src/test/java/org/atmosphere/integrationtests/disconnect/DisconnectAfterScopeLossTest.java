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
package org.atmosphere.integrationtests.disconnect;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member's {@code @Disconnect} keeps reading its request when another thread
 * completes the lifecycle while the callback runs, in a real container.
 *
 * <p>The 2026-10-20 re-test reloaded a classroom page mid-answer and got
 * {@code IllegalStateException: Request object no longer valid} from the
 * {@code @Disconnect} callback: the member never left the room. The natural
 * interleaving cannot be reproduced on demand, so {@link LeaveRaceHandler}
 * forces it from inside the callback.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisconnectAfterScopeLossTest {

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.disconnect");
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    void tearDown() throws Exception {
        httpClient.close();
        server.close();
    }

    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @Test
    void theRoomIsToldAMemberLeftWhenItsResourceWentOutOfScopeDuringDisconnect() throws Exception {
        var classmateFrames = new CopyOnWriteArrayList<String>();
        var leftFrame = new CountDownLatch(1);
        var classmate = connect(classmateFrames, frame -> {
            if (frame.contains("left:")) {
                leftFrame.countDown();
            }
        });
        var leaver = connect(new CopyOnWriteArrayList<>(), frame -> { });
        // Let both handshakes and @Ready run.
        Thread.sleep(1_000);

        leaver.sendClose(WebSocket.NORMAL_CLOSURE, "reload").join();

        assertTrue(leftFrame.await(10, TimeUnit.SECONDS),
                "the classmate should be told the member left, got " + classmateFrames);
        assertTrue(classmateFrames.stream().anyMatch(f -> f.contains("left:math")),
                "the leave names the room read from the leaver's request: " + classmateFrames);
        classmate.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private WebSocket connect(CopyOnWriteArrayList<String> frames,
                              java.util.function.Consumer<String> onFrame) throws Exception {
        var open = new CountDownLatch(1);
        var ws = httpClient.newWebSocketBuilder().buildAsync(URI.create(server.getWebSocketUrl()
                        + "/leave-race?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                        + "&X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0"),
                new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        open.countDown();
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            var frame = buffer.toString();
                            buffer.setLength(0);
                            if (!frame.isBlank()) {
                                frames.add(frame);
                                onFrame.accept(frame);
                            }
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).join();
        assertTrue(open.await(5, TimeUnit.SECONDS), "WebSocket should connect");
        return ws;
    }
}
