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
package org.atmosphere.util;

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.Enumeration;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleBroadcasterTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "test";
            }

            @Override
            public ServletContext getServletContext() {
                return Mockito.mock(ServletContext.class);
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
        config = framework.getAtmosphereConfig();
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void initializeReturnsBroadcasterWithId() {
        var broadcaster = new SimpleBroadcaster();
        Broadcaster result = broadcaster.initialize("test-id", config);

        assertNotNull(result);
        assertEquals("test-id", broadcaster.getID());
    }

    @Test
    void broadcastReturnsNonNullFuture() {
        var broadcaster = new SimpleBroadcaster();
        broadcaster.initialize("broadcast-test", config);

        Future<Object> future = broadcaster.broadcast("hello");

        assertNotNull(future);
        assertTrue(future.isDone());
    }

    @Test
    void broadcastAfterDestroyReturnsCompletedFuture() {
        var broadcaster = new SimpleBroadcaster();
        broadcaster.initialize("destroy-test", config);
        broadcaster.destroy();

        Future<Object> future = broadcaster.broadcast("msg");

        assertNotNull(future);
        assertTrue(future.isDone());
    }

    @Test
    void broadcastFilteredToNullReturnsNull() {
        var broadcaster = new SimpleBroadcaster();
        broadcaster.initialize("filter-test", config);
        // Add a filter that returns null to simulate filtering out
        broadcaster.getBroadcasterConfig().addFilter(
                new org.atmosphere.cpr.BroadcastFilter() {
                    @Override
                    public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
                        return new BroadcastAction(BroadcastAction.ACTION.ABORT, null);
                    }
                });

        Future<Object> future = broadcaster.broadcast("filtered");

        assertNull(future);
    }

    @Test
    void toStringContainsBroadcasterName() {
        var broadcaster = new SimpleBroadcaster();
        broadcaster.initialize("tostring-test", config);

        String str = broadcaster.toString();

        assertNotNull(str);
        assertTrue(str.contains("tostring-test"));
    }

    @Test
    void defaultConstructorCreatesInstance() {
        var broadcaster = new SimpleBroadcaster();
        assertNotNull(broadcaster);
    }

    @Test
    void broadcasterConfigNotNull() {
        var broadcaster = new SimpleBroadcaster();
        broadcaster.initialize("config-test", config);

        assertNotNull(broadcaster.getBroadcasterConfig());
    }
}
