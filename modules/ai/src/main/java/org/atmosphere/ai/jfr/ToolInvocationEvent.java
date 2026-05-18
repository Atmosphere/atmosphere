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
 * A single tool execution by an agent. The outcome is one of:
 * <ul>
 *   <li>{@code SUCCESS} — tool returned without exception</li>
 *   <li>{@code FAILURE} — tool threw or returned an error result</li>
 *   <li>{@code DENIED}  — {@code ToolPermissionPolicy} blocked execution
 *       (commit #2 in the agent-runtime-jfr-permissions-memory branch)</li>
 * </ul>
 *
 * <p>Emitted from {@link org.atmosphere.ai.jfr.JfrAiMetrics#recordToolCall}
 * for every runtime adapter that already feeds the
 * {@link org.atmosphere.ai.AiMetrics} chain.</p>
 */
@Name("org.atmosphere.ai.ToolInvocation")
@Label("Atmosphere AI Tool Invocation")
@Description("One tool execution by an Atmosphere AI agent")
@Category({"Atmosphere", "AI", "Tools"})
@StackTrace(false)
public final class ToolInvocationEvent extends Event {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_DENIED = "DENIED";

    @Label("Tool")
    public String tool;

    @Label("Model")
    public String model;

    @Label("Outcome")
    @Description("SUCCESS / FAILURE / DENIED")
    public String outcome;

    @Label("Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long durationNanos;
}
