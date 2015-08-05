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
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class ConcurrentBroadcasterTest {

    private AtmosphereResource ar;
    private DefaultBroadcaster broadcaster;
    private AR atmosphereHandler;
    private AtmosphereConfig config;
    
    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS, "true")
                .getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.destroy();
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
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

    public final static class AR2 implements AtmosphereHandler {

        public AtomicInteger count = new AtomicInteger();

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            count.incrementAndGet();
            // logger.info("Message received => " + count);
            //logger.info(e.getMessage());
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    public void testOrderedConcurrentBroadcast() throws InterruptedException {
        long t1 = System.currentTimeMillis();
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(broadcaster.getBroadcasterConfig().getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
        final CountDownLatch latch = new CountDownLatch(1000);
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
        });

        StringBuffer b = new StringBuffer();
        for (int i = 0; i < 1000; i++) {
            b.append("message-" + i);
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(atmosphereHandler.value.get().toString(), b.toString());
    }

    @Test
    public void testMultipleConcurrentBroadcast() throws InterruptedException {
        long t1 = System.currentTimeMillis();
        AR2 a = new AR2();
        int count = 50;
        int client = 100;
        for (int i = 0; i < client; i++) {
            broadcaster.addAtmosphereResource(newAR(a));
        }

        final CountDownLatch latch = new CountDownLatch(count);
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
        });

        for (int i = 0; i < count; i++) {
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(a.count.get(), count * client);
        //Thread.sleep(600000);
    }

    @Test
    public void testMultipleNonOrderedConcurrentBroadcast() throws InterruptedException {
        AtmosphereConfig config = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS, "true")
                .addInitParameter(ApplicationConfig.OUT_OF_ORDER_BROADCAST, "true")
                .getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = (DefaultBroadcaster) factory.get(DefaultBroadcaster.class, "test");

        long t1 = System.currentTimeMillis();
        AR2 a = new AR2();
        int count = 50;
        int client = 100;
        for (int i = 0; i < client; i++) {
            broadcaster.addAtmosphereResource(newAR(a));
        }

        final CountDownLatch latch = new CountDownLatch(count);
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
        });

        for (int i = 0; i < count; i++) {
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(a.count.get(), count * client);
        //Thread.sleep(600000);
    }

    @Test
    public void testMultipleNonOrderedSimpleBroadcast() throws InterruptedException {
        AtmosphereConfig config = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS, "true")
                .addInitParameter(ApplicationConfig.OUT_OF_ORDER_BROADCAST, "true")
                .getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(SimpleBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = (DefaultBroadcaster) factory.get(SimpleBroadcaster.class, "test");

        long t1 = System.currentTimeMillis();
        AR2 a = new AR2();
        int count = 50;
        int client = 100;
        for (int i = 0; i < client; i++) {
            broadcaster.addAtmosphereResource(newAR(a));
        }

        final CountDownLatch latch = new CountDownLatch(count);
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
        });

        for (int i = 0; i < count; i++) {
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(a.count.get(), count * client);
        //Thread.sleep(600000);
    }

    @Test
    public void testMultipleOrderedSimpleBroadcast() throws InterruptedException {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(SimpleBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = (DefaultBroadcaster) factory.get(SimpleBroadcaster.class, "test");

        long t1 = System.currentTimeMillis();
        AR2 a = new AR2();
        int count = 50;
        int client = 100;
        for (int i = 0; i < client; i++) {
            broadcaster.addAtmosphereResource(newAR(a));
        }

        final CountDownLatch latch = new CountDownLatch(count);
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
        });

        for (int i = 0; i < count; i++) {
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(a.count.get(), count * client);
        //Thread.sleep(600000);
    }

    @Test
    public void testOrderedSimpleBroadcast() throws InterruptedException {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(SimpleBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = (DefaultBroadcaster) factory.get(SimpleBroadcaster.class, "test");

        long t1 = System.currentTimeMillis();
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(broadcaster.getBroadcasterConfig().getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
        final CountDownLatch latch = new CountDownLatch(1000);
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
        });

        StringBuffer b = new StringBuffer();
        for (int i = 0; i < 1000; i++) {
            b.append("message-" + i);
            broadcaster.broadcast("message-" + i);
        }
        latch.await(60, TimeUnit.SECONDS);

        assertEquals(atmosphereHandler.value.get().toString(), b.toString());
    }

    AtmosphereResource newAR(AtmosphereHandler a) {
        return new AtmosphereResourceImpl(broadcaster.getBroadcasterConfig().getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                a);
    }
}
