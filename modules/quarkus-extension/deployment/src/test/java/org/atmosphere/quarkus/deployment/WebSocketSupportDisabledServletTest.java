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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code quarkus.atmosphere.websocket-support=false} with
 * {@code quarkus.atmosphere.container=servlet}: the framework runs with WebSocket
 * disabled, a WebSocket connection is refused, and HTTP transports keep working.
 * Before this key was wired it was declared but never read, so it did nothing.
 */
public class WebSocketSupportDisabledServletTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(WebSocketSupportDisabledServletTest.class, VertxContainerTest.class, VertxContainerTest.Chat.class))
            .overrideConfigKey("quarkus.atmosphere.container", "servlet")
            .overrideConfigKey("quarkus.atmosphere.websocket-support", "false")
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/atmosphere/chat")
    URL chatUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void frameworkRunsWithWebSocketDisabled() {
        assertFalse(LazyAtmosphereConfigurator.getFramework().webSocketEnabled());
    }

    @Test
    public void webSocketConnectionIsRefused() {
        var uri = URI.create(chatUrl.toString().replaceFirst("^http", "ws")
                + "?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=0");
        var failure = assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder()
                .buildAsync(uri, new WebSocket.Listener() { }).get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof java.net.http.WebSocketHandshakeException
                        || failure.getCause() instanceof CompletionException
                        || failure.getCause() instanceof java.io.IOException,
                "expected a refused handshake, got " + failure.getCause());
    }

    @Test
    public void longPollingStillWorks() throws Exception {
        VertxContainerTest.Chat.suspended = new CountDownLatch(1);
        var poll = http.sendAsync(HttpRequest.newBuilder(URI.create(chatUrl
                        + "?X-Atmosphere-Transport=long-polling&X-Atmosphere-tracking-id=0")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(VertxContainerTest.Chat.suspended.await(10, TimeUnit.SECONDS), "the GET must suspend");
        var post = http.send(HttpRequest.newBuilder(URI.create(chatUrl
                        + "?X-Atmosphere-Transport=polling&X-Atmosphere-tracking-id=0"))
                .POST(HttpRequest.BodyPublishers.ofString("fallback")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, post.statusCode());
        var res = poll.get(10, TimeUnit.SECONDS);
        assertTrue(res.body().contains("msg:fallback"), res.body());
    }
}
