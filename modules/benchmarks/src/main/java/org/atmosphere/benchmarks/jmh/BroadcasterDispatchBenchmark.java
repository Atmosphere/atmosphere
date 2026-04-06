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
package org.atmosphere.benchmarks.jmh;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Tier 1 micro-benchmark: {@link DefaultBroadcaster} dispatch overhead per
 * subscriber. Measures broadcast throughput as the subscriber count scales
 * from 1 to 1000.
 *
 * <p>The {@link NoOpHandler} absorbs all state-change events so the benchmark
 * isolates dispatch overhead from I/O cost.</p>
 */
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class BroadcasterDispatchBenchmark {

    @Param({"1", "10", "100", "1000"})
    public int subscriberCount;

    private Broadcaster broadcaster;

    @Setup(Level.Trial)
    @SuppressWarnings("deprecation")
    public void setup() throws Exception {
        AtmosphereFramework framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.BROADCASTER_CACHE_STRATEGY, "beforeFilter");
        AtmosphereConfig config = framework.getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get("bench-dispatch");
        config.framework().setBroadcasterFactory(factory);

        var handler = new NoOpHandler();
        var asyncSupport = new StubAsyncSupport(config);

        for (int i = 0; i < subscriberCount; i++) {
            AtmosphereResourceImpl ar = new AtmosphereResourceImpl(
                    config, broadcaster,
                    AtmosphereRequestImpl.newInstance(),
                    AtmosphereResponseImpl.newInstance(),
                    asyncSupport, handler);
            broadcaster.addAtmosphereResource(ar);
        }
    }

    @Benchmark
    public Object broadcastToAll() {
        return broadcaster.broadcast("hello-benchmark");
    }

    /** No-op handler — absorbs all events without I/O. */
    private static final class NoOpHandler implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            // no-op
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            // no-op
        }

        @Override
        public void destroy() {
            // no-op
        }
    }

    /** Minimal async support stub for constructing AtmosphereResourceImpl. */
    private static final class StubAsyncSupport implements AsyncSupport<AtmosphereResourceImpl> {

        StubAsyncSupport(AtmosphereConfig config) {
            // config accepted for API compatibility but not needed in stub
        }

        @Override
        public String getContainerName() {
            return "benchmark-stub";
        }

        @Override
        public void init(ServletConfig sc) throws ServletException {
            // no-op
        }

        @Override
        public Action service(AtmosphereRequest req, AtmosphereResponse res)
                throws IOException, ServletException {
            return Action.CONTINUE;
        }

        @Override
        public void action(AtmosphereResourceImpl actionEvent) {
            // no-op
        }

        @Override
        public boolean supportWebSocket() {
            return false;
        }

        @Override
        public AsyncSupport<AtmosphereResourceImpl> complete(AtmosphereResourceImpl r) {
            return this;
        }
    }
}
