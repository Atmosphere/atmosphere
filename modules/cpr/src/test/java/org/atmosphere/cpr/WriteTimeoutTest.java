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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class WriteTimeoutTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;
    private AtmosphereConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework()
            .addInitParameter("org.atmosphere.cpr.Broadcaster.writeTimeout", "2000")
            .addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true")
            .init().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.destroy();
        config.getBroadcasterFactory().destroy();
    }

    @Test
    public void testWriteTimeout() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch guard = new CountDownLatch(1);

        atmosphereHandler = new AR(latch);
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
        atmosphereHandler = new AR(null);
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

    public final static class AR implements AtmosphereHandler {

        private final CountDownLatch latch;

        public AR(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {

        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            try {
                if (latch != null) latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }

        @Override
        public void destroy() {
        }
    }
}
