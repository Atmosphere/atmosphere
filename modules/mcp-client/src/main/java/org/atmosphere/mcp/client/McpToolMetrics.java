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
package org.atmosphere.mcp.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tool dispatch counters captured by {@link McpToolSource}'s executor
 * wrapper. Surfaced to operators through the admin/control-plane endpoint
 * the sample exposes — see {@code samples/spring-boot-personal-assistant}'s
 * {@code McpClientAdminController}.
 *
 * <p>The fields are independent atomic counters; reads are not transactional
 * across them. The intent is operator-grade visibility ("how many calls did
 * tool X get and how many failed?"), not exact accounting — the model loop
 * is the source of truth for billing/audit.</p>
 */
public final class McpToolMetrics {

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong lastLatencyMs = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    void recordCall(long latencyMs) {
        calls.incrementAndGet();
        lastLatencyMs.set(latencyMs);
        totalLatencyMs.addAndGet(latencyMs);
    }

    void recordError() {
        errors.incrementAndGet();
    }

    /** Number of times this tool's executor was invoked. */
    public long calls() {
        return calls.get();
    }

    /** Number of invocations that returned a server-reported tool error or threw. */
    public long errors() {
        return errors.get();
    }

    /** Latency in milliseconds of the most recent call (0 if never invoked). */
    public long lastLatencyMs() {
        return lastLatencyMs.get();
    }

    /**
     * Mean call latency in milliseconds across all calls (0 if never invoked).
     * Computed lazily from the running totals — not a streaming average.
     */
    public long avgLatencyMs() {
        var c = calls.get();
        return c == 0 ? 0 : totalLatencyMs.get() / c;
    }
}
