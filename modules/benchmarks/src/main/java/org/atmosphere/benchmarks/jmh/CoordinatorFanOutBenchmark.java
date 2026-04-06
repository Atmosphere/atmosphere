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

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
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
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tier 1 micro-benchmark: coordinator parallel fan-out latency using
 * {@link DefaultAgentFleet#parallel(AgentCall...)}. Measures the overhead
 * of dispatching N concurrent agent calls through the fleet abstraction
 * with a zero-latency transport stub.
 *
 * <p>The {@link NoLatencyTransport} returns a canned result immediately,
 * so this benchmark isolates fleet dispatch and virtual-thread scheduling
 * overhead from actual agent execution cost.</p>
 */
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CoordinatorFanOutBenchmark {

    @Param({"2", "4", "8", "16"})
    public int fanOutCount;

    private AgentFleet fleet;
    private AgentCall[] calls;

    @Setup(Level.Trial)
    public void setup() {
        var transport = new NoLatencyTransport();
        var proxies = new LinkedHashMap<String, AgentProxy>();
        calls = new AgentCall[fanOutCount];

        for (int i = 0; i < fanOutCount; i++) {
            var name = "agent-" + i;
            proxies.put(name, new DefaultAgentProxy(
                    name, "1.0.0", 1, true, transport));
            calls[i] = new AgentCall(name, "benchmark-skill", Map.of("index", i));
        }

        fleet = new DefaultAgentFleet(proxies);
    }

    @Benchmark
    public void parallelFanOut(Blackhole bh) {
        bh.consume(fleet.parallel(calls));
    }

    /**
     * Transport that returns a canned success result with zero latency.
     * Isolates fleet dispatch overhead from network or compute cost.
     */
    private static final class NoLatencyTransport implements AgentTransport {

        private static final Duration ZERO_DURATION = Duration.ZERO;

        @Override
        public AgentResult send(String agentName, String skill, Map<String, Object> args) {
            return new AgentResult(agentName, skill, "benchmark-result",
                    Map.of(), ZERO_DURATION, true);
        }

        @Override
        public void stream(String agentName, String skill, Map<String, Object> args,
                           Consumer<String> onToken, Runnable onComplete) {
            onToken.accept("benchmark-token");
            onComplete.run();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
