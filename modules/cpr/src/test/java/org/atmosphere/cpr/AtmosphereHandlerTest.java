/*
 * Copyright 2015 Jean-Francois Arcand
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
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class AtmosphereHandlerTest {
    private AtmosphereFramework framework;

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init(new ServletConfig() {
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


    @Test
    public void tesOnStateChange() throws IOException, ServletException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<AtmosphereResourceEvent> e = new AtomicReference<AtmosphereResourceEvent>();

        framework.addAtmosphereHandler("/a", new AtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.suspend();
                r.set(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                e.set(event);
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        r.get().resume();

        assertTrue(e.get().isResuming());
        framework.destroy();
    }

    @Test
    public void testByteCachedList() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        f.setBroadcasterFactory(new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", f.getAtmosphereConfig()));
        assertNotNull(f.getBroadcasterFactory());
        broadcaster = f.getBroadcasterFactory().get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();

        final AtomicReference<byte[]> ref = new AtomicReference<byte[]>();
        AtmosphereResponse r = AtmosphereResponseImpl.newInstance();
        r.asyncIOWriter(new AsyncIOWriterAdapter() {
            @Override
            public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
                ref.set(data);
                return this;
            }
        });
        ar = new AtmosphereResourceImpl(f.getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                r,
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);


        broadcaster.addAtmosphereResource(ar);

        List<byte[]> l = new ArrayList<byte[]>();
        l.add("yo".getBytes());
        broadcaster.broadcast(l).get();
        assertEquals("yo", new String(ref.get()));
    }

    public final static class AR extends AbstractReflectorAtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }
}
