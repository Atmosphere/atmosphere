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

import org.atmosphere.cache.EventCacheBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class EventCacheBroadcasterCacheTest {
    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;
    private EventCacheBroadcasterCache eventCacheBroadcasterCache;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        config.framework().setBroadcasterFactory(factory);

        eventCacheBroadcasterCache = new EventCacheBroadcasterCache();
        broadcaster.getBroadcasterConfig().setBroadcasterCache(eventCacheBroadcasterCache);
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                AtmosphereResponse.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);
        broadcaster.addAtmosphereResource(ar);
    }

    @AfterMethod
    public void addAR(){
        broadcaster.removeAtmosphereResource(ar);
    }

    @Test
    public void testBasicCache() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.broadcast("e1").get();
        broadcaster.removeAtmosphereResource(ar);
        broadcaster.broadcast("e2").get();
        broadcaster.broadcast("e3").get();

        assertEquals(2, eventCacheBroadcasterCache.messages().get(ar.uuid()).getQueue().size());
    }

    @Test
    public void addRemoveAddTest() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.broadcast("e1");
        broadcaster.removeAtmosphereResource(ar);
        broadcaster.broadcast("e2").get();
        assertEquals(1, eventCacheBroadcasterCache.messages().size());

        broadcaster.addAtmosphereResource(ar);
        broadcaster.broadcast("e3").get();

        assertEquals(0, eventCacheBroadcasterCache.messages().get(ar.uuid()).getQueue().size());
    }

    @Test
    public void nothingCachedTest() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.removeAtmosphereResource(ar);

        broadcaster.broadcast("e1");
        broadcaster.broadcast("e2");

        assertEquals(0, eventCacheBroadcasterCache.messages().size());
    }

    @Test
    public void concurrentCache() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(100);
        broadcaster.addBroadcasterListener(new BroadcasterListener() {
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
        }).broadcast("e-1");

        broadcaster.removeAtmosphereResource(ar);

        ExecutorService s = Executors.newCachedThreadPool();
        for (int i=0; i < 99; i++) {
            s.submit(new Runnable() {
                @Override
                public void run() {
                    broadcaster.broadcast("e");
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        assertEquals(100, eventCacheBroadcasterCache.messages().get(ar.uuid()).getQueue().size());
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
