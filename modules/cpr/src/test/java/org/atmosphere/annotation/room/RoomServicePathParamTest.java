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
package org.atmosphere.annotation.room;

import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.RoomService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @PathParam} used to resolve only on a {@code @ManagedService} class: both
 * {@code ManagedServiceInterceptor.managed()} and {@code PathParamIntrospector.injectable()}
 * branched on {@code @ManagedService} alone, so a templated {@code @RoomService} injected null.
 *
 * <p>These tests pin the templated-path contract for {@code @RoomService}.</p>
 */
public class RoomServicePathParamTest {

    private AtmosphereFramework framework;
    private static final AtomicReference<String> captured = new AtomicReference<>();

    @BeforeEach
    public void create() throws Throwable {
        captured.set(null);
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(RoomChat.class);
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            @Override
            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException | ServletException e) {
                    throw new IllegalStateException(e);
                }
            }
        }).init();
    }

    @AfterEach
    public void after() {
        captured.set(null);
        framework.destroy();
    }

    @RoomService(path = "/room/{roomId}/chat")
    public final static class RoomChat {

        @PathParam("roomId")
        private String roomId;

        @Get
        public void get(AtmosphereResource resource) {
            captured.set(roomId);
        }
    }

    @Test
    public void pathParamResolvesOnRoomService() throws IOException, ServletException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .pathInfo("/room/math/chat").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertNotNull(captured.get(),
                "@PathParam injected null on a templated @RoomService — the path template was never resolved");
        assertEquals("math", captured.get());
    }

    @Test
    public void pathParamIsPerRoomNotSharedAcrossPaths() throws IOException, ServletException {
        framework.doCometSupport(new AtmosphereRequestImpl.Builder()
                .pathInfo("/room/math/chat").method("GET").build(), AtmosphereResponseImpl.newInstance());
        assertEquals("math", captured.get());

        framework.doCometSupport(new AtmosphereRequestImpl.Builder()
                .pathInfo("/room/history/chat").method("GET").build(), AtmosphereResponseImpl.newInstance());
        assertEquals("history", captured.get(),
                "the second room saw the first room's @PathParam value — the templated handler is being shared");
    }
}
