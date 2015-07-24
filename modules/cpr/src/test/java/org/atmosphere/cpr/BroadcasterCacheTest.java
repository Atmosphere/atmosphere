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

import org.atmosphere.cache.AbstractBroadcasterCache;
import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

public class BroadcasterCacheTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;
    private final AtomicReference<List<CacheMessage>> cachedMessage = new AtomicReference<List<CacheMessage>>();
    private AtmosphereConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        config.framework().setBroadcasterFactory(factory);

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
    public void unSetUp() throws Exception {
        broadcaster.removeAtmosphereResource(ar);
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testRejectedCache() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public CacheMessage addToCache(String id, String uuid, BroadcastMessage e) {
                CacheMessage c = put(e, System.nanoTime(), uuid);
                cachedMessage.set(messages);
                return c;
            }

            @Override
            public List<Object> retrieveFromCache(String id, String uuid) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcastMessage message) {
                return false;
            }
        });

        broadcaster.broadcast("foo", ar).get();
        assertEquals(cachedMessage.get().size(), 0);
    }

    @Test
    public void testCache() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public CacheMessage addToCache(String id, String uuid, BroadcastMessage e) {
                CacheMessage c = put(e, System.nanoTime(), uuid);
                cachedMessage.set(messages);
                return c;
            }

            @Override
            public List<Object> retrieveFromCache(String id, String uuid) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcastMessage message) {
                return true;
            }
        });

        broadcaster.broadcast("foo", ar).get();
        assertEquals(cachedMessage.get().size(), 0);
    }

    @Test
    public void testEmptyRejectedCache() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(1);
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public CacheMessage addToCache(String id, String uuid, BroadcastMessage e) {
                CacheMessage c = put(e, System.nanoTime(), uuid);
                cachedMessage.set(messages);
                latch.countDown();
                return c;
            }

            @Override
            public List<Object> retrieveFromCache(String id, String uuid) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcastMessage message) {
                return false;
            }
        });

        broadcaster.broadcast("foo", ar);
        latch.await(10, TimeUnit.SECONDS);
        assertEquals(cachedMessage.get().size(), 0);
    }

    @Test
    public void testEmptyCache() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(1);
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public CacheMessage addToCache(String id, String uuid, BroadcastMessage e) {
                CacheMessage c = put(e, System.nanoTime(), uuid);
                cachedMessage.set(messages);
                latch.countDown();
                return c;
            }

            @Override
            public List<Object> retrieveFromCache(String id, String uuid) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcastMessage message) {
                return true;
            }
        });

        broadcaster.broadcast("foo", ar);
        latch.await(10, TimeUnit.SECONDS);
        assertEquals(cachedMessage.get().size(), 1);
    }

    public final static class AR implements AtmosphereHandler {


        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    public void testBasicExcludeCache() throws ExecutionException, InterruptedException, ServletException {
        BroadcasterCache cache = new UUIDBroadcasterCache();
        cache.configure(config);

        AtmosphereResource r = config.resourcesFactory().create(broadcaster.getBroadcasterConfig().getAtmosphereConfig(), "1234567");

        cache.excludeFromCache(broadcaster.getID(), r);

        broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
        broadcaster.removeAtmosphereResource(r);
        broadcaster.broadcast("foo").get();

        List<Object> l = cache.retrieveFromCache(broadcaster.getID(), ar.uuid());
        assertNotNull(l);
        assertEquals(l.isEmpty(), true);
    }

    @Test
    public void testExcludeCache() throws ExecutionException, InterruptedException, ServletException {
        BroadcasterCache cache = new UUIDBroadcasterCache();
        cache.configure(config);

        AtmosphereResource r = config.resourcesFactory().create(broadcaster.getBroadcasterConfig().getAtmosphereConfig(), "1234567");

        broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
        broadcaster.addAtmosphereResource(r);
        broadcaster.broadcast("foo").get();
        broadcaster.removeAtmosphereResource(r);
        broadcaster.broadcast("foo").get();

        List<Object> l = cache.retrieveFromCache(broadcaster.getID(), r.uuid());
        assertNotNull(l);
        assertEquals(l.isEmpty(), false);
    }

    @Test
    public void testCloseExcludeCache() throws ExecutionException, InterruptedException, ServletException, IOException {
        UUIDBroadcasterCache cache = new UUIDBroadcasterCache();
        SimpleBroadcaster b = config.getBroadcasterFactory().lookup(SimpleBroadcaster.class, "uuidTest", true);
        cache.configure(config);

        b.getBroadcasterConfig().setBroadcasterCache(cache);
        // Reset
        b.removeAtmosphereResource(ar);

        b.addAtmosphereResource(ar);
        b.broadcast("foo").get();

        ar.close();
        b.removeAtmosphereResource(ar);

        b.broadcast("raide").get();

        assertEquals(cache.messages().isEmpty(), false);
        List<Object> l = cache.retrieveFromCache(b.getID(), ar.uuid());
        assertNotNull(l);
        assertEquals(l.isEmpty(), false);
    }

    @Test
    public void testSuspendExcludeCache() throws ExecutionException, InterruptedException, ServletException, IOException {
        UUIDBroadcasterCache cache = new UUIDBroadcasterCache();
        SimpleBroadcaster b = config.getBroadcasterFactory().lookup(SimpleBroadcaster.class, "uuidTest", true);
        cache.configure(config);

        b.getBroadcasterConfig().setBroadcasterCache(cache);
        // Reset
        b.removeAtmosphereResource(ar);

        ar.suspend();
        b.removeAtmosphereResource(ar);

        b.broadcast("raide").get();

        // Blocked by the cache because suspend has been called.
        assertEquals(cache.messages().isEmpty(), true);
    }
}
