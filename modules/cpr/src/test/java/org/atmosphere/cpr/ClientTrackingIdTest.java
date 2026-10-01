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
package org.atmosphere.cpr;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.atmosphere.container.BlockingIOCometSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Pins what the server does with the tracking id a client sends on its first
 * request, which atmosphere.js relies on and documents: its SSE, streaming and
 * long-polling transports send an id of their own when {@code enableProtocol}
 * is off. Only {@code 0} (or no header) makes the server mint the id through the
 * application's {@link org.atmosphere.util.UUIDProvider} and mark the response
 * as the first one; a well-formed client id is adopted as the connection's uuid
 * as is, so a custom provider never sees that connection.
 */
public class ClientTrackingIdTest {

    private AtmosphereFramework framework;
    private final AtomicInteger minted = new AtomicInteger();
    private final AtomicReference<String> uuid = new AtomicReference<>();
    private final AtomicReference<String> trackingHeader = new AtomicReference<>();
    private final AtomicReference<String> firstRequestHeader = new AtomicReference<>();

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.uuidProvider(() -> "app-minted-" + minted.incrementAndGet());
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) {
                uuid.set(resource.uuid());
                trackingHeader.set(resource.getResponse().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID));
                firstRequestHeader.set(resource.getResponse().getHeader(HeaderConfig.X_FIRST_REQUEST));
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) {
            }

            @Override
            public void destroy() {
            }
        });
    }

    private void firstGet(String trackingId) throws Exception {
        var request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET")
                .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, trackingId)).build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
    }

    @Test
    public void trackingIdZeroIsMintedByTheApplicationProvider() throws Exception {
        firstGet("0");

        assertEquals("app-minted-1", uuid.get());
        assertEquals("app-minted-1", trackingHeader.get());
        assertEquals("true", firstRequestHeader.get());
    }

    @Test
    public void clientChosenTrackingIdIsAdoptedWithoutTheProvider() throws Exception {
        firstGet("0b6c1f8e-5d2a-4c3b-9e7f-client");

        assertEquals("0b6c1f8e-5d2a-4c3b-9e7f-client", uuid.get());
        assertEquals(0, minted.get(), "the application's UUIDProvider is not consulted for a client-chosen id");
        assertNull(firstRequestHeader.get(),
                "a request carrying its own id is not marked as the first one");
    }
}
