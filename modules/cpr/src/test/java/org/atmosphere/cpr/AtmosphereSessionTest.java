/*
 * Copyright 2015 Async-IO.org
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
import org.atmosphere.handler.AtmosphereHandlerAdapter;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class AtmosphereSessionTest {

    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
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
    }

    @Test
    public void testTrackAndTryAcquire() throws IOException, ServletException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicReference<AtmosphereSession> session = new AtomicReference<AtmosphereSession>();
        framework.addAtmosphereHandler("/acquire", new AtmosphereHandlerAdapter() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                if (session.get() == null) {
                    session.set(new AtmosphereSession(resource));
                }
                resource.suspend(2, TimeUnit.SECONDS);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                latch.countDown();
            }
        });
        final String qs = "&X-Atmosphere-tracking-id=c8834462-c46e-4dad-a22f-b86aabe3f883&X-Atmosphere-Framework=2.0.4-javascript&X-Atmosphere-Transport=sse&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true&_=1380799455333";
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(qs).pathInfo("/acquire").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        latch.await(10, TimeUnit.SECONDS);
        assertNull(session.get().acquire());

        final AtomicReference<AtmosphereResource> rrr = new AtomicReference<AtmosphereResource>();
        final CountDownLatch _latch = new CountDownLatch(1);
        framework.addAtmosphereHandler("/acquire", new AtmosphereHandlerAdapter() {
            @Override
            public void onRequest(final AtmosphereResource resource) throws IOException {
                resource.suspend(2, TimeUnit.SECONDS);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                try {
                    rrr.set(session.get().tryAcquire());
                    _latch.countDown();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        request = new AtmosphereRequestImpl.Builder().queryString(qs).pathInfo("/acquire").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance(request));

        _latch.await(10, TimeUnit.SECONDS);

        assertNotNull(rrr.get());

        new Thread() {
            public void run() {
                try {
                    Thread.sleep(1000);
                    AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(qs).pathInfo("/acquire").build();
                    framework.doCometSupport(request, AtmosphereResponseImpl.newInstance(request));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();

        assertNotNull(session.get().tryAcquire());


    }

}
