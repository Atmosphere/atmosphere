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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tier 1 micro-benchmark: cost of traversing the {@link AiInterceptor} chain
 * during streaming, mirroring the pre/post-process loop in
 * {@code AiStreamingSession}. Measures chain dispatch overhead independent
 * of the underlying LLM call.
 *
 * <p>The {@code resource} argument is {@code null} here: the no-op
 * interceptors used below don't dereference it. Real interceptors do, so
 * this benchmark establishes a <em>lower bound</em> on chain cost.</p>
 */
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class AiInterceptorChainBenchmark {

    @Param({"0", "1", "4", "16", "64"})
    public int chainLength;

    private List<AiInterceptor> chain;
    private AiRequest request;

    @Setup(Level.Trial)
    public void setup() {
        chain = new ArrayList<>(chainLength);
        for (int i = 0; i < chainLength; i++) {
            chain.add(new NoOpInterceptor());
        }
        request = new AiRequest(
                "hello world",
                "you are a helpful assistant",
                "gpt-4",
                "user-1",
                "session-1",
                "agent-1",
                "conv-1",
                Map.of(),
                List.of()
        );
    }

    @Benchmark
    public AiRequest preProcessChain() {
        AiRequest current = request;
        // FIFO traversal, matching AiStreamingSession preProcess loop.
        for (AiInterceptor interceptor : chain) {
            current = interceptor.preProcess(current, (AtmosphereResource) null);
        }
        return current;
    }

    @Benchmark
    public void postProcessChain(Blackhole bh) {
        // LIFO traversal, matching AiStreamingSession postProcess loop.
        // Consume each interceptor reference so the JIT cannot dead-code-eliminate
        // the default no-op postProcess method call.
        for (int i = chain.size() - 1; i >= 0; i--) {
            AiInterceptor interceptor = chain.get(i);
            interceptor.postProcess(request, (AtmosphereResource) null);
            bh.consume(interceptor);
        }
    }

    private static final class NoOpInterceptor implements AiInterceptor {
        // Inherits default preProcess / postProcess / onDisconnect.
    }
}
