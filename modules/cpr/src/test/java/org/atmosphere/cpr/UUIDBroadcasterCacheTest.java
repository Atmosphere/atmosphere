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

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.ExecutorsFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class UUIDBroadcasterCacheTest {
    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;
    private UUIDBroadcasterCache broadcasterCache;
    private AtmosphereConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        config.framework().setBroadcasterFactory(factory);

        broadcasterCache = new UUIDBroadcasterCache();
        broadcaster.getBroadcasterConfig().setBroadcasterCache(broadcasterCache);
        broadcasterCache.configure(config);
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);
        broadcaster.addAtmosphereResource(ar);
    }

    @AfterMethod
    public void addAR() {
        broadcaster.removeAtmosphereResource(ar);
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testBasicCache() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.broadcast("e1").get();
        broadcaster.removeAtmosphereResource(ar);
        broadcaster.broadcast("e2").get();
        broadcaster.broadcast("e3").get();

        assertEquals(broadcasterCache.messages().get(ar.uuid()).getQueue().size(), 2);
    }

    @Test
    public void addRemoveAddTest() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.broadcast("e1").get();
        broadcaster.removeAtmosphereResource(ar);
        broadcaster.broadcast("e2").get();
        assertEquals(1, broadcasterCache.messages().size());

        broadcaster.addAtmosphereResource(ar);
        broadcaster.broadcast("e3").get();

        assertEquals(broadcasterCache.messages().size(), 1);
        assertEquals(broadcasterCache.messages().get(ar.uuid()).getQueue().size(), 1);
    }

    @Test
    public void everythingCachedTest() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.removeAtmosphereResource(ar);

        broadcaster.broadcast("e1").get();
        broadcaster.broadcast("e2").get();

        assertEquals(1, broadcasterCache.messages().size());
    }

    @Test
    public void concurrentCache() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(101);
        broadcaster.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
            }

            @Override
            public void onComplete(Broadcaster b) {
                latch.countDown();
            }

            @Override
            public void onPreDestroy(Broadcaster b) {
            }
        }).broadcast("e-1").get();

        broadcaster.removeAtmosphereResource(ar);

        ExecutorService s = Executors.newCachedThreadPool();
        final AtomicInteger y = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            s.submit(new Runnable() {
                @Override
                public void run() {
                    broadcaster.broadcast("e" + y.getAndIncrement());
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        assertEquals(broadcasterCache.messages().get(ar.uuid()).getQueue().size(), 100);
    }

    public final static class AR implements AtmosphereHandler {

        public AtomicReference<StringBuffer> value = new AtomicReference<StringBuffer>(new StringBuffer());

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            value.get().append(e.getMessage());
        }

        @Override
        public void destroy() {
        }
    }


}
