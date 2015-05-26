/*
 * Copyright 2015 Jason Burgess
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

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.atmosphere.pool.BoundedApachePoolableProvider;
import org.atmosphere.pool.PoolableBroadcasterFactory;
import org.atmosphere.pool.UnboundedApachePoolableProvider;
import org.atmosphere.util.ExecutorsFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for the {@link PoolableBroadcasterFactory}.
 *
 * @author Jason Burgess
 */
public class PoolableBroadcasterFactoryTest {

    private AtmosphereConfig config;
    private PoolableBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        config = f.getAtmosphereConfig();
        factory = new PoolableBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        factory.poolableProvider(new BoundedApachePoolableProvider());
        f.setBroadcasterFactory(factory);
    }

    @AfterMethod
    public void unSet() throws Exception {
        config.destroy();
        ExecutorsFactory.reset(config);
        factory.destroy();
    }

    @Test
    public void testGet_0args() {
        Broadcaster result = factory.get();
        assert result != null;
        assert result instanceof DefaultBroadcaster;
    }

    @Test
    public void testGet_Object() {
        String id = "id";
        Broadcaster result = factory.get(id);
        assert result != null;
        assert result instanceof DefaultBroadcaster;
        assert id.equals(result.getID());
    }

    @Test
    public void testAddRemove() {
        Broadcaster result = factory.get();
        assert result != null;
        assert result instanceof DefaultBroadcaster;

        result.destroy();
        Broadcaster result2 = factory.get();

        assert result2 != null;
        assert result2 instanceof DefaultBroadcaster;
        assertEquals(result2, result);
    }

    @Test
    public void testImplementation() {
        assertNotNull(factory.poolableProvider());
        assertNotNull(factory.poolableProvider().implementation());
        assertEquals(factory.poolableProvider().implementation().getClass(), GenericObjectPool.class);
        GenericObjectPool nativePool = (GenericObjectPool) factory.poolableProvider().implementation();
        assertTrue(nativePool.getLifo());
        GenericObjectPoolConfig c = new GenericObjectPoolConfig();
        c.setMaxTotal(1);
        nativePool.setConfig(c);
        assertEquals(1, nativePool.getMaxTotal());
    }

    @Test
    public void concurrentLookupTest() throws InterruptedException {
        String id = "id";
        final CountDownLatch latch = new CountDownLatch(100);
        final AtomicInteger created = new AtomicInteger();

        factory.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
                created.incrementAndGet();
            }

            @Override
            public void onComplete(Broadcaster b) {

            }

            @Override
            public void onPreDestroy(Broadcaster b) {

            }
        });

        final ConcurrentLinkedQueue<Broadcaster> c = new ConcurrentLinkedQueue<Broadcaster>();
        ExecutorService r = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            r.submit(new Runnable() {
                @Override
                public void run() {
                    c.add(factory.lookup("name" + UUID.randomUUID().toString(), true));
                    latch.countDown();
                }
            });
        }

        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            assertEquals(created.get(), 100);
            assertEquals(c.size(), 100);

            for (Broadcaster b : c) {
                b.destroy();
            }

            assertNotNull(factory.lookup("name" + UUID.randomUUID().toString(), true).broadcast("test"));

            assertEquals(factory.poolableProvider().poolSize(), 100);

        } finally {
            factory.destroy();
            r.shutdownNow();
        }

    }

    @Test
    public void concurrentAccessLookupTest() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1000);
        final AtomicInteger created = new AtomicInteger();
        factory.poolableProvider(new UnboundedApachePoolableProvider());
        factory.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
                created.incrementAndGet();
            }

            @Override
            public void onComplete(Broadcaster b) {

            }

            @Override
            public void onPreDestroy(Broadcaster b) {

            }
        });

        final ConcurrentLinkedQueue<Broadcaster> c = new ConcurrentLinkedQueue<Broadcaster>();
        ExecutorService r = Executors.newCachedThreadPool();
        final String me = new String("me");
        for (int i = 0; i < 1000; i++) {
            r.submit(new Runnable() {
                @Override
                public void run() {
                    c.add(factory.get(me));
                    latch.countDown();
                }
            });

        }
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            assertEquals(latch.getCount(), 0);
            assertEquals(c.size(), 1000);
            assertEquals(created.get(), 1000);

            for (Broadcaster b : c) {
                b.destroy();
            }

            assertNotNull(factory.lookup("name" + UUID.randomUUID().toString(), true).broadcast("test"));

            assertEquals(factory.poolableProvider().poolSize(), 1000);


        } finally {
            factory.destroy();
            r.shutdownNow();
        }

    }
}
