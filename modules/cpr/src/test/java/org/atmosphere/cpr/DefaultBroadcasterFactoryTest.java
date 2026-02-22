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

import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.SimpleBroadcaster;

import java.util.UUID;
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
 * Unit tests for the {@link org.atmosphere.cpr.DefaultBroadcasterFactory}.
 *
 * @author Jason Burgess
 */
public class DefaultBroadcasterFactoryTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
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
    public void testGet_Object_Twice() {
            assertThrows(IllegalStateException.class, () -> {
            String id = "id";
            factory.get(id);
            factory.get(id);
            });
    }

    @Test
    public void testAdd() {
        String id = "id";
        String id2 = "foo";
        Broadcaster b = factory.get(id);
        assert factory.add(b, id) == false;
        assert factory.lookup(id) != null;
        assert factory.add(b, id2) == true;
        assert factory.lookup(id2) != null;
    }

    @Test
    public void testRemove() {
        String id = "id";
        String id2 = "foo";
        Broadcaster b = factory.get(id);
        Broadcaster b2 = factory.get(id2);
        assert factory.remove(b, id2) == false;
        assert factory.remove(b2, id) == false;
        assert factory.remove(b, id) == true;
        assert factory.lookup(id) == null;
    }

    @Test
    public void testLookup_Class_Object() {
        String id = "id";
        String id2 = "foo";
        assert factory.lookup(DefaultBroadcaster.class, id, true) != null;
        assert factory.lookup(DefaultBroadcaster.class, id2) == null;
    }

    @Test
    public void testLookup_Class_Object_BadClass() {
            assertThrows(IllegalStateException.class, () -> {
            String id = "id";
            factory.get(id);
            factory.lookup(SimpleBroadcaster.class, id);
            });
    }

    @Test
    public void testLookup_Object() {
        String id = "id";
        String id2 = "foo";
        factory.get(id);
        assert factory.lookup(id) != null;
        assert factory.lookup(id2) == null;
    }

    @Test
    public void testLookup_Object_boolean() {
        String id = "id";
        assert factory.lookup(id, false) == null;
        Broadcaster b = factory.lookup(id, true);
        assert b != null;
        assert id.equals(b.getID());
    }

    @Test
    public void testLookup_Class_Object_boolean() {
        String id = "id";
        assert factory.lookup(DefaultBroadcaster.class, id, false) == null;
        Broadcaster b = factory.lookup(DefaultBroadcaster.class, id, true);
        assert b != null;
        assert b instanceof DefaultBroadcaster;
        assert id.equals(b.getID());
    }

    @Test
    public void testSlashFactory() {
        factory.lookup("/atmosphere", true);
        factory.lookup("/atmosphere", true);

        assertEquals(1, factory.lookupAll().size());
    }

    @Test
    public void testEmailFactory() {
        factory.lookup("/atmosphere@atmosphere.com", true);
        factory.lookup("/atmosphere@atmosphere.com", true);
        factory.lookup("/atmosphere", true);

        assertEquals(2, factory.lookupAll().size());
    }

    @Test
    public void testSlashEmailFactory() {
        factory.lookup("/atmosphere@atmosphere.com", true);
        factory.lookup("/atmosphere@atmosphere.com", true);

        assertEquals(1, factory.lookupAll().size());
    }

    @Test
    public void concurrentLookupTest() throws InterruptedException {
        final DefaultBroadcasterFactory f = new DefaultBroadcasterFactory();
        f.configure(DefaultBroadcaster.class, "NEVER", config);
        final CountDownLatch latch = new CountDownLatch(100);
        final AtomicInteger created = new AtomicInteger();

        f.addBroadcasterListener(new BroadcasterListenerAdapter() {
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

        ExecutorService r = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            r.submit(new Runnable() {
                @Override
                public void run() {
                    f.lookup("name" + UUID.randomUUID().toString(), true);
                    latch.countDown();
                }
            });
        }
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            assertEquals(100, f.lookupAll().size());
            assertEquals(100, created.get());
        } finally {
            f.destroy();
        }
    }

    @Test
    public void concurrentAccessLookupTest() throws InterruptedException {
        final DefaultBroadcasterFactory f = new DefaultBroadcasterFactory();
        f.configure(DefaultBroadcaster.class, "NEVER", config);
        final CountDownLatch latch = new CountDownLatch(1000);
        final AtomicInteger created = new AtomicInteger();
        f.addBroadcasterListener(new BroadcasterListenerAdapter() {
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

        ExecutorService r = Executors.newCachedThreadPool();
        final String me = new String("me");
        for (int i = 0; i < 1000; i++) {
            r.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        f.get(TestBroadcaster.class, me);
                    } finally {
                        latch.countDown();
                    }
                }
            });

        }

        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            assertEquals(0, latch.getCount());
            assertEquals(1, f.lookupAll().size());
            assertEquals(1, created.get());
            assertEquals(1, TestBroadcaster.instance.get());
        } finally {
            f.destroy();
            r.shutdownNow();
        }

        assertEquals(1, TestBroadcaster.instance.get());

    }

    public final static class TestBroadcaster extends DefaultBroadcaster {

        public static AtomicInteger instance = new AtomicInteger();

        public TestBroadcaster() {
            instance.incrementAndGet();
        }
    }
}
