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

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
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

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tier 1 micro-benchmark: {@link InMemoryCheckpointStore} operations across
 * varying snapshot counts. Measures save throughput and load/fork/list latency
 * to quantify per-operation overhead as the store scales.
 */
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CheckpointStoreBenchmark {

    @Param({"100", "1000", "10000"})
    public int snapshotCount;

    private InMemoryCheckpointStore store;
    private CheckpointId[] existingIds;
    private String coordinationId;
    private Instant benchmarkEpoch;

    @Setup(Level.Trial)
    public void setup() {
        // Very large cap so saves/forks during the benchmark don't trigger eviction
        // (which would dominate measurements with its O(N log N) sort).
        store = new InMemoryCheckpointStore(Integer.MAX_VALUE);
        existingIds = new CheckpointId[snapshotCount];
        coordinationId = "bench-coord";
        benchmarkEpoch = Instant.now();
        var now = Instant.now();
        for (int i = 0; i < snapshotCount; i++) {
            var snap = WorkflowSnapshot.<byte[]>builder()
                    .id(CheckpointId.random())
                    .coordinationId(coordinationId)
                    .agentName("agent-" + (i % 16))
                    .state(new byte[1024])
                    .createdAt(now.plusMillis(i))
                    .build();
            store.save(snap);
            existingIds[i] = snap.id();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public WorkflowSnapshot<byte[]> saveHot() {
        var snap = WorkflowSnapshot.<byte[]>builder()
                .id(CheckpointId.random())
                .coordinationId(coordinationId)
                .agentName("bench")
                .state(new byte[1024])
                .createdAt(benchmarkEpoch)
                .build();
        return store.save(snap);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void loadRandom(Blackhole bh) {
        var idx = ThreadLocalRandom.current().nextInt(snapshotCount);
        bh.consume(store.load(existingIds[idx]));
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public WorkflowSnapshot<byte[]> forkChain() {
        var idx = ThreadLocalRandom.current().nextInt(snapshotCount);
        return store.fork(existingIds[idx], new byte[1024]);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void listByCoordination(Blackhole bh) {
        bh.consume(store.list(CheckpointQuery.forCoordination(coordinationId)));
    }
}
