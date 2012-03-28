/*
 * Copyright 2012 Jean-Francois Arcand
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.w3c.dom.stylesheets.LinkStyle;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class BroadcasterTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                mock(AtmosphereResponse.class),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.removeAtmosphereResource(ar);
    }

    @Test
    public void testDirectBroadcastMethod() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.broadcast("foo", ar).get();
        assertEquals(atmosphereHandler.value.get().toArray()[0], ar);
    }

    @Test
    public void testSetBroadcastMethod() throws ExecutionException, InterruptedException, ServletException {
        AtmosphereConfig config = new AtmosphereFramework()
                .setCometSupport(mock(BlockingIOCometSupport.class))
                .init(new ServletConfig() {
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
                })
                .getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                mock(AtmosphereResponse.class),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);
        AtmosphereResource ar2 = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                mock(AtmosphereResponse.class),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);
        AtmosphereResource ar3 = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                mock(AtmosphereResponse.class),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar).addAtmosphereResource(ar2).addAtmosphereResource(ar3);

        Set<AtmosphereResource> set = new HashSet<AtmosphereResource>();
        set.add(ar);
        set.add(ar2);

        broadcaster.broadcast("foo", set).get();

        assertEquals(atmosphereHandler.value.get(), set);
    }

    public final static class AR implements AtmosphereHandler {

        public AtomicReference<Set> value = new AtomicReference<Set>(new HashSet());

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            value.get().add(e.getResource());
        }

        @Override
        public void destroy() {
        }
    }
}
