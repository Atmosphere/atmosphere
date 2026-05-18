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

/**
 * One delegation from a coordinator to a sub-agent in the fleet. Emitted by
 * {@code DefaultAgentFleet} around each individual dispatch so a recording
 * shows both the fan-out parallelism and the first-run sequential warm-up
 * pattern (see {@link #firstRun}).
 */
@Name("org.atmosphere.ai.SubAgentDispatch")
@Label("Atmosphere AI Sub-Agent Dispatch")
@Description("Coordinator delegation to a fleet sub-agent")
@Category({"Atmosphere", "AI", "Coordinator"})
@StackTrace(false)
public final class SubAgentDispatchEvent extends Event {

    @Label("Sub-Agent")
    @Description("Fleet entry name of the dispatched agent")
    public String subAgent;

    @Label("Skill")
    public String skill;

    @Label("First Run")
    @Description("True on the first successful dispatch in this JVM (sequential warm-up path)")
    public boolean firstRun;

    @Label("Success")
    public boolean success;
}
