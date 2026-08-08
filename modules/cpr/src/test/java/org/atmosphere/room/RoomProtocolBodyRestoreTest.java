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
package org.atmosphere.room;

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins that this interceptor leaves the request body readable for whoever runs next.
 *
 * <p>It reads the body to look for a room command. Reading consumes the servlet
 * input stream, so unless the content is written back, a downstream
 * {@code @Message} handler re-reads a drained stream and fails with
 * {@code Stream closed} — a chat message sent over long-polling or SSE never
 * reaches the annotated method, while the same message over WebSocket works,
 * because there the body is already cached.</p>
 *
 * <p>The asymmetry is why this went unnoticed: the sibling suite builds requests
 * with {@code request.body(String)}, which populates the cached body and takes
 * the WebSocket branch. Only a stream-backed request reaches the code that broke,
 * and the assertion has to run through {@code inspect} — asserting the contract
 * in the test body instead would pass with the fix removed.</p>
 */
class RoomProtocolBodyRestoreTest {

    private AtmosphereConfig config;
    private RoomProtocolInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        RoomManager.getOrCreate(config.framework());

        interceptor = new RoomProtocolInterceptor();
        interceptor.configure(config);
    }

    /** An HTTP-shaped request: payload in the input stream, cached body empty. */
    private static AtmosphereRequest streamBackedRequest(String payload) {
        return new AtmosphereRequestImpl.Builder()
                .method("POST")
                .pathInfo("/chat")
                .inputStream(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private AtmosphereResource resourceFor(AtmosphereRequest request) throws IOException {
        Broadcaster b = config.getBroadcasterFactory()
                .get(DefaultBroadcaster.class, "restore-" + System.nanoTime());
        return new AtmosphereResourceImpl(config, b, request,
                AtmosphereResponseImpl.newInstance(),
                mock(AsyncSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) { }
                    @Override public void onStateChange(AtmosphereResourceEvent event) { }
                    @Override public void destroy() { }
                });
    }

    @Test
    void theFixtureIsStreamBackedNotCached() {
        var request = streamBackedRequest("plain chat text");

        assertTrue(request.body().isEmpty(),
                "an HTTP request carries its payload in the input stream — if the cached "
                        + "body is already populated this fixture takes the WebSocket "
                        + "branch and proves nothing");
    }

    @Test
    void inspectLeavesANonCommandBodyReadable() throws Exception {
        var payload = "{\"author\":\"alice\",\"message\":\"over http\"}";
        var request = streamBackedRequest(payload);

        // Drives the real code path: inspect() -> readBody() -> consumes the stream.
        interceptor.inspect(resourceFor(request));

        assertTrue(request.body().hasString(),
                "after the interceptor read the body it must be restored, or the next "
                        + "consumer re-reads a drained stream and the annotated method "
                        + "never runs");
        assertEquals(payload, request.body().asString(),
                "the restored body must be exactly the bytes that were read");
    }

    @Test
    void aRoomCommandBodyIsAlsoRestored() throws Exception {
        // A recognised command still consumes the stream on its way in; whether the
        // interceptor acts on it or not, it must not leave the request drained.
        var payload = "{\"type\":\"not-a-real-command\",\"room\":\"lobby\"}";
        var request = streamBackedRequest(payload);

        interceptor.inspect(resourceFor(request));

        assertTrue(request.body().hasString(),
                "the restore must not depend on whether the body parsed as a command");
        assertEquals(payload, request.body().asString());
    }
}
