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
package org.atmosphere.metrics;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AtmosphereMetricsTest {

    private AtmosphereConfig config;
    private Broadcaster broadcaster;
    private SimpleMeterRegistry registry;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        registry = new SimpleMeterRegistry();
        AtmosphereMetrics.install(config.framework(), registry);

        // Create broadcaster AFTER metrics are installed so onPostCreate fires
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
    }

    @AfterMethod
    public void tearDown() throws Exception {
        broadcaster.destroy();
        config.getBroadcasterFactory().destroy();
        ExecutorsFactory.reset(config);
        registry.close();
    }

    @Test
    public void testActiveBroadcastersGauge() {
        Gauge gauge = registry.find("atmosphere.broadcasters.active").gauge();
        assertTrue(gauge != null, "broadcasters.active gauge should be registered");
        // The broadcaster created in setUp should be counted
        assertTrue(gauge.value() >= 1, "Should have at least 1 active broadcaster");
    }

    @Test
    public void testConnectionCounters() throws Exception {
        var latch = new CountDownLatch(1);
        var ar = createResource(latch);
        broadcaster.addAtmosphereResource(ar);

        Gauge activeGauge = registry.find("atmosphere.connections.active").gauge();
        Counter totalCounter = registry.find("atmosphere.connections.total").counter();

        assertTrue(activeGauge != null, "connections.active gauge should be registered");
        assertTrue(totalCounter != null, "connections.total counter should be registered");
        assertEquals(activeGauge.value(), 1.0, "Should have 1 active connection");
        assertEquals(totalCounter.count(), 1.0, "Should have 1 total connection");
    }

    @Test
    public void testMessagesBroadcastCounter() throws Exception {
        var latch = new CountDownLatch(1);
        var ar = createResource(latch);
        broadcaster.addAtmosphereResource(ar);

        broadcaster.broadcast("hello").get();
        latch.await(5, TimeUnit.SECONDS);

        Counter counter = registry.find("atmosphere.messages.broadcast").counter();
        assertTrue(counter != null, "messages.broadcast counter should be registered");
        assertEquals(counter.count(), 1.0, "Should have 1 message broadcast");
    }

    @Test
    public void testBroadcastTimer() throws Exception {
        var latch = new CountDownLatch(1);
        var ar = createResource(latch);
        broadcaster.addAtmosphereResource(ar);

        broadcaster.broadcast("hello").get();
        latch.await(5, TimeUnit.SECONDS);

        Timer timer = registry.find("atmosphere.broadcast.timer").timer();
        assertTrue(timer != null, "broadcast.timer should be registered");
        assertEquals(timer.count(), 1L, "Should have 1 timer recording");
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0, "Timer should have recorded time");
    }

    @Test
    public void testConnectionRemovalDecrementsGauge() throws Exception {
        var latch = new CountDownLatch(1);
        var ar = createResource(latch);
        broadcaster.addAtmosphereResource(ar);

        Gauge activeGauge = registry.find("atmosphere.connections.active").gauge();
        assertEquals(activeGauge.value(), 1.0);

        broadcaster.removeAtmosphereResource(ar);
        assertEquals(activeGauge.value(), 0.0, "Should have 0 active connections after removal");
    }

    private AtmosphereResource createResource(CountDownLatch latch) throws IOException {
        return new AtmosphereResourceImpl(config,
                broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new AtmosphereHandler() {
                    @Override
                    public void onRequest(AtmosphereResource resource) throws IOException {}

                    @Override
                    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                        latch.countDown();
                    }

                    @Override
                    public void destroy() {}
                });
    }
}
