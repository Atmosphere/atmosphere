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
package org.atmosphere.ai.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * Latency summary for a completed model call. Fields mirror the
 * {@link org.atmosphere.ai.AiMetrics#recordLatency} signal so every runtime
 * that already feeds Micrometer counts also feeds JFR.
 *
 * <p>Two timespans are recorded: {@code timeToFirstToken} (TTFT) and
 * {@code totalDuration}. Both are pre-measured by the runtime adapter; the
 * JFR event itself has no measured begin/commit duration.</p>
 */
@Name("org.atmosphere.ai.Call")
@Label("Atmosphere AI Model Call")
@Description("Latency summary for one model call (TTFT + total duration)")
@Category({"Atmosphere", "AI"})
@StackTrace(false)
public final class AiCallEvent extends Event {

    @Label("Model")
    public String model;

    @Label("Time to First Token")
    @Timespan(Timespan.NANOSECONDS)
    public long timeToFirstTokenNanos;

    @Label("Total Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long totalDurationNanos;
}
