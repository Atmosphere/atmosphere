/*
 * Copyright 2013 Jean-Francois Arcand
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
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertTrue;

public class AtmosphereResourceListenerTest {
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
    public void testListenersCallback() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").build();

        final AtomicReference<Boolean> suspended = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> resumed = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> disconnected = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> preSuspended = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> broadcasted = new AtomicReference<Boolean>();

        final AtmosphereResourceEventListener listener = new AtmosphereResourceEventListener() {

            @Override
            public void onPreSuspend(AtmosphereResourceEvent event) {
                preSuspended.set(true);
            }

            @Override
            public void onSuspend(AtmosphereResourceEvent event) {
                suspended.set(true);
            }

            @Override
            public void onResume(AtmosphereResourceEvent event) {
                resumed.set(true);
            }

            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                disconnected.set(true);
            }

            @Override
            public void onBroadcast(AtmosphereResourceEvent event) {
                broadcasted.set(true);
            }

            @Override
            public void onThrowable(AtmosphereResourceEvent event) {
            }

            @Override
            public void onClose(AtmosphereResourceEvent event) {
            }
        };

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                r.addEventListener(listener).suspend();
                try {
                    r.getBroadcaster().broadcast("void").get();
                } catch (InterruptedException e) {
                } catch (ExecutionException e) {
                }
                return Action.CONTINUE;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
                r.resume();
            }
        });
        framework.doCometSupport(request, AtmosphereResponse.newInstance());


        assertTrue(preSuspended.get());
        assertTrue(suspended.get());
        assertTrue(resumed.get());
        assertTrue(broadcasted.get());
    }

    @Test
    public void testOnClose() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").build();

        final AtomicReference<Boolean> closed = new AtomicReference<Boolean>();

        final AtmosphereResourceEventListener listener = new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onClose(AtmosphereResourceEvent event) {
                closed.set(true);
            }
        };

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                r.addEventListener(listener).suspend();
                try {
                    r.getBroadcaster().broadcast("void").get();
                } catch (InterruptedException e) {
                } catch (ExecutionException e) {
                }
                return Action.CONTINUE;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
                try {
                    r.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertTrue(closed.get());
    }
}
