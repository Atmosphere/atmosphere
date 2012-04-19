/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.tests.http;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AtmosphereServletTest {

    @Test
    public void destroy() throws Exception {
        Broadcaster broadcaster = mock(Broadcaster.class);

        AtmosphereServlet servlet = new AtmosphereServlet();
        Handler handler = new Handler();
        Handler handler2 = new Handler();

        assertFalse(handler.isDestroyed());
        assertFalse(handler2.isDestroyed());

        servlet.framework().addAtmosphereHandler("/test", handler, broadcaster);
        servlet.framework().addAtmosphereHandler("/test2", handler2, broadcaster);

        servlet.destroy();

        assertTrue(handler.isDestroyed());
        assertTrue(handler2.isDestroyed());
    }

    private static class Handler implements AtmosphereHandler {

        private final AtomicBoolean destroyed = new AtomicBoolean(false);

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        public boolean isDestroyed() {
            return destroyed.get();
        }
    }
}
