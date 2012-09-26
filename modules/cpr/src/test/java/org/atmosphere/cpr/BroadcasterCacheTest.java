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

import org.atmosphere.cache.AbstractBroadcasterCache;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.container.BlockingIOCometSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BroadcasterCacheTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;
    private final AtomicReference<List<CacheMessage>> cachedMessage = new AtomicReference<List<CacheMessage>>();

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                AtmosphereResponse.create(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.removeAtmosphereResource(ar);
    }

    @Test
    public void testRejectedCache() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public void addToCache(String id, AtmosphereResource r, Message e) {
                put(e, System.nanoTime());
                cachedMessage.set(messages);
            }

            @Override
            public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcasterCache.Message message) {
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
            public void addToCache(String id, AtmosphereResource r, Message e) {
                put(e, System.nanoTime());
                cachedMessage.set(messages);
            }

            @Override
            public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcasterCache.Message message) {
                return true;
            }
        });

        broadcaster.broadcast("foo", ar).get();
        assertEquals(cachedMessage.get().size(), 1);
    }

    @Test
    public void testEmptyRejectedCache() throws ExecutionException, InterruptedException, ServletException {
        final CountDownLatch latch = new CountDownLatch(1);
        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public void addToCache(String id, AtmosphereResource r, Message e) {
                put(e, System.nanoTime());
                cachedMessage.set(messages);
                latch.countDown();
            }

            @Override
            public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcasterCache.Message message) {
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
            public void addToCache(String id, AtmosphereResource r, Message e) {
                put(e, System.nanoTime());
                cachedMessage.set(messages);
                latch.countDown();
            }

            @Override
            public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
                return Collections.<Object>emptyList();
            }
        }).getBroadcasterCache().inspector(new BroadcasterCacheInspector() {
            @Override
            public boolean inspect(BroadcasterCache.Message message) {
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
}
