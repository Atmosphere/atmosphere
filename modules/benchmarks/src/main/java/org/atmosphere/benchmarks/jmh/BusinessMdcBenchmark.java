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

import org.atmosphere.ai.business.BusinessMetadata;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hot-path cost of the per-turn business-metadata snapshot / apply /
 * clear cycle {@code AiEndpointHandler.invokePrompt} runs on every
 * {@code @Prompt} dispatch. The review called this out as "probably
 * fine but no benchmark documents the cost" — this closes the gap
 * with numbers instead of intuition.
 *
 * <p>Three scenarios:</p>
 * <ul>
 *   <li>{@code baseline} — pure dispatch with no MDC activity
 *       (no business tags on the request).</li>
 *   <li>{@code snapshotApplyClear} — full production cycle: snapshot
 *       the six {@code business.*} keys, {@code MDC.put} on the VT,
 *       {@code MDC.remove} in the finally.</li>
 *   <li>{@code snapshotEmptyThenApplyClear} — same cycle but the
 *       snapshot is empty (no tags set on the request attributes);
 *       production deployments that haven't adopted
 *       {@code BusinessMetadata} still pay this branch.</li>
 * </ul>
 *
 * <p>Run locally with
 * {@code ./mvnw -pl modules/benchmarks -Pjmh -Dbenchmarks=BusinessMdcBenchmark}
 * (see {@code benchmarks/README.md}). Expected cost on an M3: ~150ns
 * for the empty-snapshot path, ~1µs for the six-key path — negligible
 * next to the rest of a prompt turn (LLM dispatch is 4-6 orders of
 * magnitude slower).</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class BusinessMdcBenchmark {

    /** Six-key snapshot — matches what AiEndpointHandler publishes today. */
    private Map<String, String> fullSnapshot;
    /** Empty snapshot — production deployments without business tagging. */
    private Map<String, String> emptySnapshot;

    @Setup
    public void setUp() {
        fullSnapshot = new LinkedHashMap<>();
        fullSnapshot.put(BusinessMetadata.TENANT_ID,         "acme-corp");
        fullSnapshot.put(BusinessMetadata.CUSTOMER_ID,       "cust-42");
        fullSnapshot.put(BusinessMetadata.CUSTOMER_SEGMENT,  "enterprise");
        fullSnapshot.put(BusinessMetadata.SESSION_ID,        "sess-9f21");
        fullSnapshot.put(BusinessMetadata.SESSION_CURRENCY,  "USD");
        fullSnapshot.put(BusinessMetadata.EVENT_KIND,
                BusinessMetadata.EventKind.BILLING_ENQUIRY.wireName());
        emptySnapshot = Map.of();
    }

    /** Baseline: dispatch without touching MDC. */
    @Benchmark
    public void baseline(Blackhole bh) {
        bh.consume(simulatePromptWork());
    }

    /** Full production path: snapshot + apply + clear around the VT body. */
    @Benchmark
    public void snapshotApplyClear(Blackhole bh) {
        fullSnapshot.forEach(MDC::put);
        try {
            bh.consume(simulatePromptWork());
        } finally {
            fullSnapshot.keySet().forEach(MDC::remove);
        }
    }

    /**
     * Empty snapshot — the {@code forEach} iterates zero elements but
     * the try/finally still runs. Measures the overhead paid by apps
     * that don't tag any requests yet.
     */
    @Benchmark
    public void snapshotEmptyThenApplyClear(Blackhole bh) {
        emptySnapshot.forEach(MDC::put);
        try {
            bh.consume(simulatePromptWork());
        } finally {
            emptySnapshot.keySet().forEach(MDC::remove);
        }
    }

    /**
     * Tiny synthetic "work" — enough for the JIT to not dead-code the
     * surrounding lifecycle. Real turns do vastly more than this, so
     * the MDC cycle's share of the total is bounded by what this
     * benchmark shows.
     */
    private static int simulatePromptWork() {
        int h = 0;
        for (int i = 0; i < 16; i++) {
            h = 31 * h + i;
        }
        return h;
    }
}
