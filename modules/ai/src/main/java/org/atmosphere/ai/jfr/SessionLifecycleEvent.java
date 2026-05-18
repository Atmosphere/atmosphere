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
 * One streaming session lifecycle transition. The {@code transition} field is
 * either {@code STARTED} or {@code ENDED} so a single recording can be
 * collapsed into a session-duration view by pairing matching events.
 */
@Name("org.atmosphere.ai.SessionLifecycle")
@Label("Atmosphere AI Session Lifecycle")
@Description("Streaming session start/end transitions")
@Category({"Atmosphere", "AI"})
@StackTrace(false)
public final class SessionLifecycleEvent extends Event {

    public static final String TRANSITION_STARTED = "STARTED";
    public static final String TRANSITION_ENDED = "ENDED";

    @Label("Model")
    public String model;

    @Label("Transition")
    @Description("STARTED or ENDED")
    public String transition;
}
