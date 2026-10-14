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
package org.atmosphere.session.frames;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.session.DurableSession;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.InMemorySessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * A long-polling client sends its tracking id on every poll, and the runtime
 * makes it the uuid of each poll's resource. A client that polls without the
 * session token (it has not read it yet, or never reads it) keeps the one
 * durable session its connection got on the first poll.
 */
class DurableSessionLongPollingTest {

    @ManagedService(path = "/durable")
    public static final class ChatService {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    /** Records every session token the interceptor saved. */
    static final class CountingStore extends InMemorySessionStore {

        final Set<String> tokens = ConcurrentHashMap.newKeySet();

        @Override
        public void save(DurableSession session) {
            tokens.add(session.token());
            super.save(session);
        }
    }

    private AtmosphereFramework framework;
    private CountingStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new CountingStore();
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(ChatService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.interceptor(new DurableSessionInterceptor(store));
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
            }
        });
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
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    private AtmosphereResponse poll(String trackingId) throws Exception {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false)
                .pathInfo("/durable")
                .headers(Map.of(
                        HeaderConfig.X_ATMOSPHERE_TRACKING_ID, trackingId,
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT))
                .build();
        var response = AtmosphereResponseImpl.newInstance(request);
        framework.doCometSupport(request, response);
        return response;
    }

    @Test
    void pollsWithoutTheTokenKeepTheConnectionsSingleDurableSession() throws Exception {
        var first = poll("0");
        var trackingId = first.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        assertNotNull(trackingId, "the first poll is assigned a tracking id");
        assertNotNull(first.getHeader(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER),
                "the first poll is sent its session token");
        assertEquals(1, store.tokens.size());

        for (int i = 0; i < 3; i++) {
            var next = poll(trackingId);
            assertNull(next.getHeader(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER),
                    "a poll that did not present the token is not sent it");
        }

        assertEquals(1, store.tokens.size(),
                "polls of one connection without the session token must keep one durable session");
    }
}
