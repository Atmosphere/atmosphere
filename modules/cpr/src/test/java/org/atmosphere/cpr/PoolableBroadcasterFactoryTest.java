/*
 * Copyright 2008-2026 Async-IO.org
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

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link PoolableBroadcasterFactory}.
 *
 * @author Jason Burgess
 */
public class PoolableBroadcasterFactoryTest {

    private AtmosphereConfig config;
    private PoolableBroadcasterFactory factory;

    @BeforeEach
    public void setUp() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        config = f.getAtmosphereConfig();
        factory = new PoolableBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        factory.poolableProvider(new BoundedApachePoolableProvider());
        f.setBroadcasterFactory(factory);
    }

    @AfterEach
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
        assertEquals(result, result2);
    }

    @Test
    public void testImplementation() {
        assertNotNull(factory.poolableProvider());
        assertNotNull(factory.poolableProvider().implementation());
        assertEquals(GenericObjectPool.class, factory.poolableProvider().implementation().getClass());
        @SuppressWarnings("unchecked")
        GenericObjectPool<Broadcaster> nativePool = (GenericObjectPool<Broadcaster>) factory.poolableProvider().implementation();
        assertTrue(nativePool.getLifo());
        GenericObjectPoolConfig<Broadcaster> c = new GenericObjectPoolConfig<>();
        c.setMaxTotal(1);
        nativePool.setConfig(c);
        assertEquals(nativePool.getMaxTotal(), 1);
    }

    @Test
    public void concurrentLookupTest() throws InterruptedException {
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
            assertEquals(100, created.get());
            assertEquals(100, c.size());

            for (Broadcaster b : c) {
                b.destroy();
            }

            assertNotNull(factory.lookup("name" + UUID.randomUUID().toString(), true).broadcast("test"));

            assertEquals(100, factory.poolableProvider().poolSize());

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
            assertEquals(0, latch.getCount());
            assertEquals(1000, c.size());
            assertEquals(1000, created.get());

            for (Broadcaster b : c) {
                b.destroy();
            }

            assertNotNull(factory.lookup("name" + UUID.randomUUID().toString(), true).broadcast("test"));

            assertEquals(1000, factory.poolableProvider().poolSize());

        } finally {
            factory.destroy();
            r.shutdownNow();
        }

    }
}
