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
 * A runtime-classified error. {@code errorType} matches the values reported
 * to {@link org.atmosphere.ai.AiMetrics#recordError} ({@code rate_limit},
 * {@code timeout}, {@code server_error}, {@code stream_error}, ...).
 */
@Name("org.atmosphere.ai.Error")
@Label("Atmosphere AI Error")
@Description("A classified AI runtime error")
@Category({"Atmosphere", "AI"})
@StackTrace(false)
public final class AiErrorEvent extends Event {

    @Label("Model")
    public String model;

    @Label("Error Type")
    public String errorType;
}
