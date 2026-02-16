/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereFrameworkListener;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.Deliver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metrics integration for Atmosphere.
 *
 * <p>Registers gauges, counters and timers on an Atmosphere framework instance.
 * Requires {@code io.micrometer:micrometer-core} on the classpath (optional dependency).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MeterRegistry registry = new SimpleMeterRegistry();
 * AtmosphereMetrics.install(framework, registry);
 * }</pre>
 *
 * <h3>Metrics published</h3>
 * <ul>
 *   <li>{@code atmosphere.connections.active} — gauge of active connections</li>
 *   <li>{@code atmosphere.connections.total} — counter of all connections ever</li>
 *   <li>{@code atmosphere.connections.disconnects} — counter of disconnects</li>
 *   <li>{@code atmosphere.broadcasters.active} — gauge of active broadcasters</li>
 *   <li>{@code atmosphere.messages.broadcast} — counter of messages broadcast</li>
 *   <li>{@code atmosphere.messages.delivered} — counter of messages delivered to resources</li>
 *   <li>{@code atmosphere.broadcast.timer} — timer of broadcast completion latency</li>
 * </ul>
 */
public final class AtmosphereMetrics {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereMetrics.class);

    private final MeterRegistry registry;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger activeBroadcasters = new AtomicInteger();

    private final Counter totalConnections;
    private final Counter disconnects;
    private final Counter messagesBroadcast;
    private final Counter messagesDelivered;
    private final Timer broadcastTimer;

    private AtmosphereMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("atmosphere.connections.active", activeConnections, AtomicInteger::get)
                .description("Active Atmosphere connections")
                .register(registry);

        Gauge.builder("atmosphere.broadcasters.active", activeBroadcasters, AtomicInteger::get)
                .description("Active Atmosphere broadcasters")
                .register(registry);

        this.totalConnections = Counter.builder("atmosphere.connections.total")
                .description("Total connections opened")
                .register(registry);

        this.disconnects = Counter.builder("atmosphere.connections.disconnects")
                .description("Total disconnects")
                .register(registry);

        this.messagesBroadcast = Counter.builder("atmosphere.messages.broadcast")
                .description("Total messages broadcast")
                .register(registry);

        this.messagesDelivered = Counter.builder("atmosphere.messages.delivered")
                .description("Total messages delivered to resources")
                .register(registry);

        this.broadcastTimer = Timer.builder("atmosphere.broadcast.timer")
                .description("Broadcast completion latency")
                .register(registry);
    }

    /**
     * Install metrics collection on the given framework.
     *
     * @param framework the Atmosphere framework instance
     * @param registry  the Micrometer meter registry
     * @return the metrics instance (for testing or manual removal)
     */
    public static AtmosphereMetrics install(AtmosphereFramework framework, MeterRegistry registry) {
        var metrics = new AtmosphereMetrics(registry);

        framework.addBroadcasterListener(metrics.new MetricsBroadcasterListener());
        framework.frameworkListener(metrics.new MetricsFrameworkListener());

        logger.info("Atmosphere metrics installed on {}", registry.getClass().getSimpleName());
        return metrics;
    }

    /**
     * Tracks broadcaster lifecycle and message events.
     */
    private class MetricsBroadcasterListener extends BroadcasterListenerAdapter {

        private long broadcastStartNanos;

        @Override
        public void onPostCreate(Broadcaster b) {
            activeBroadcasters.incrementAndGet();
        }

        @Override
        public void onPreDestroy(Broadcaster b) {
            activeBroadcasters.decrementAndGet();
        }

        @Override
        public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            activeConnections.incrementAndGet();
            totalConnections.increment();

            r.addEventListener(new MetricsResourceEventListener());
        }

        @Override
        public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            activeConnections.decrementAndGet();
        }

        @Override
        public void onMessage(Broadcaster b, Deliver deliver) {
            messagesBroadcast.increment();
            broadcastStartNanos = System.nanoTime();
        }

        @Override
        public void onComplete(Broadcaster b) {
            if (broadcastStartNanos > 0) {
                broadcastTimer.record(Duration.ofNanos(System.nanoTime() - broadcastStartNanos));
                broadcastStartNanos = 0;
            }
        }
    }

    /**
     * Tracks per-resource events (broadcast delivery, disconnects).
     */
    private class MetricsResourceEventListener extends AtmosphereResourceEventListenerAdapter {

        @Override
        public void onBroadcast(AtmosphereResourceEvent event) {
            messagesDelivered.increment();
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            disconnects.increment();
        }
    }

    /**
     * Logs framework lifecycle with metrics context.
     */
    private class MetricsFrameworkListener implements AtmosphereFrameworkListener {

        @Override
        public void onPreInit(AtmosphereFramework f) {
            // no-op
        }

        @Override
        public void onPostInit(AtmosphereFramework f) {
            logger.info("Atmosphere metrics active — {} handlers registered",
                    f.getAtmosphereHandlers().size());
        }

        @Override
        public void onPreDestroy(AtmosphereFramework f) {
            // no-op
        }

        @Override
        public void onPostDestroy(AtmosphereFramework f) {
            logger.info("Atmosphere metrics shutdown — final counts: connections={}, messages={}",
                    (long) totalConnections.count(), (long) messagesBroadcast.count());
        }
    }
}
