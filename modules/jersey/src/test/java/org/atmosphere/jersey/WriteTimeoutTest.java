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
package org.atmosphere.jersey;

import com.sun.jersey.spi.container.servlet.ServletContainer;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class WriteTimeoutTest {

    private AtmosphereResource ar;
    private J broadcaster;
    private AtmosphereHandler atmosphereHandler;
    private AtmosphereConfig config;


    private final static class F extends DefaultBroadcasterFactory {

        protected F(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
            super(clazz, broadcasterLifeCyclePolicy, c);
        }
    }

    public final static class J extends JerseyBroadcaster {
        private CountDownLatch latch;

        public J() {
        }

        JerseyBroadcaster latch(CountDownLatch latch) {
            this.latch = latch;
            return this;
        }

        @Override
        protected void invokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
            try {
                if (latch != null) latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().addInitParameter("org.atmosphere.cpr.Broadcaster.writeTimeout", "2000")
                .addInitParameter("com.sun.jersey.config.property.packages", "org.atmosphere.jersey")
                .addInitParameter("org.atmosphere.useStream", "true")
                .addInitParameter("org.atmosphere.disableOnStateEvent", "true")
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
                        return Collections.enumeration(new ArrayList<String>());
                    }
                }).getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new F(J.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = (J) factory.get("test");
        atmosphereHandler = new ReflectorServletProcessor(new ServletContainer());
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.destroy();
    }

    @Test
    public void testWriteTimeout() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch guard = new CountDownLatch(1);

        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.latch(latch).addAtmosphereResource(ar);

        final AtomicReference<Throwable> t = new AtomicReference<Throwable>();
        ar.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onThrowable(AtmosphereResourceEvent event) {
                t.set(event.throwable());
                guard.countDown();
            }
        });
        broadcaster.broadcast("foo", ar).get();
        guard.await(10, TimeUnit.SECONDS);
        assertNotNull(t.get());
        assertEquals(t.get().getMessage(), "Unable to write after 2000");
    }

    @Test
    public void testNoWriteTimeout() throws ExecutionException, InterruptedException, ServletException {
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);

        final AtomicReference<Throwable> t = new AtomicReference<Throwable>();
        ar.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onThrowable(AtmosphereResourceEvent event) {
                t.set(event.throwable());
            }
        });
        broadcaster.broadcast("foo", ar).get();
        assertEquals(t.get(), null);
    }
}
