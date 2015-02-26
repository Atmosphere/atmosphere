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

import org.atmosphere.pool.PoolableBroadcasterFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;

/**
 * Unit tests for the {@link DefaultBroadcasterFactory}.
 *
 * @author Jason Burgess
 */
public class PoolableBroadcasterFactoryTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        config = f.getAtmosphereConfig();
        factory = new PoolableBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        f.setBroadcasterFactory(factory);
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
    public void concurrentLookupTest() throws InterruptedException {
        String id = "id";
        final DefaultBroadcasterFactory f = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        final CountDownLatch latch = new CountDownLatch(100);
        final AtomicInteger created = new AtomicInteger();

        f.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
                created.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onComplete(Broadcaster b) {

            }

            @Override
            public void onPreDestroy(Broadcaster b) {

            }
        });

        ExecutorService r = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < 100; i++) {
                r.submit(new Runnable() {
                    @Override
                    public void run() {
                        f.lookup("name" + UUID.randomUUID().toString(), true);
                    }
                });
            }
        } finally {
            r.shutdown();
        }
        latch.await();

        try {
            assertEquals(f.lookupAll().size(), 100);
            assertEquals(created.get(), 100);
        } finally {
            f.destroy();
        }
    }

    @Test
    public void concurrentAccessLookupTest() throws InterruptedException {
        final DefaultBroadcasterFactory f = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        final CountDownLatch latch = new CountDownLatch(1000);
        final AtomicInteger created = new AtomicInteger();
        f.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
                created.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onComplete(Broadcaster b) {

            }

            @Override
            public void onPreDestroy(Broadcaster b) {

            }
        });

        ExecutorService r = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < 1000; i++) {
                r.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            f.get(TestBroadcaster.class, new String("me"));
                        } catch (IllegalStateException ex) {
                            latch.countDown();
                        }
                    }
                });

            }
        } finally {
            r.shutdown();
        }
        latch.await(10, TimeUnit.SECONDS);
        try {
            assertEquals(latch.getCount(), 0);
            assertEquals(f.lookupAll().size(), 1);
            assertEquals(created.get(), 1);
            assertEquals(TestBroadcaster.instance.get(), 1);
        } finally {
            f.destroy();
        }

        assertEquals(TestBroadcaster.instance.get(), 1);

    }

    public final static class TestBroadcaster extends DefaultBroadcaster {

        public static AtomicInteger instance = new AtomicInteger();

        public TestBroadcaster() {
            instance.incrementAndGet();
        }
    }
}
