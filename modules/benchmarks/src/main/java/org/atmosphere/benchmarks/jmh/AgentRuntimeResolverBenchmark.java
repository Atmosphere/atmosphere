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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Tier 1 micro-benchmark: cost of resolving {@link AgentRuntimeResolver} on
 * the hot path. Exercises both the single-resolution fast path and the
 * full sorted list path. The actual provider count depends on what
 * {@code META-INF/services/org.atmosphere.ai.AgentRuntime} entries are on
 * the classpath — report the count observed from
 * {@code resolveAll().size()} alongside results.
 *
 * <p>NOTE: {@code resolve()} caches the winning runtime after the first
 * {@link java.util.ServiceLoader} scan. This benchmark therefore measures
 * the <em>cached/warm</em> resolution path (field read + priority check),
 * not cold-start discovery cost. Report {@code resolveAll().size()}
 * alongside results to document the provider count on the classpath.</p>
 *
 * <p>TODO: parameterize provider count via a synthetic {@link ClassLoader}
 * with generated service entries to isolate dispatch cost from availability
 * check cost. Tracked in Tier 1 plan (open question #4).</p>
 */
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class AgentRuntimeResolverBenchmark {

    @Benchmark
    public void resolveFirst(Blackhole bh) {
        bh.consume(AgentRuntimeResolver.resolve());
    }

    @Benchmark
    public void resolveAllSorted(Blackhole bh) {
        bh.consume(AgentRuntimeResolver.resolveAll());
    }
}
