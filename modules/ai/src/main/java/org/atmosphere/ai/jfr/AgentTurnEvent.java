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
 * One iteration of an AI agent loop: pipeline submits the assembled prompt to
 * a runtime, runtime drives memory + tool calls + streaming response, pipeline
 * commits the captured response. Duration is auto-measured by JFR between
 * {@link #begin()} and {@link #commit()}.
 *
 * <p>Emitted by {@code AiPipeline} around the {@code runtime.executeWithHandle}
 * boundary so a single turn corresponds to a single JFR event regardless of
 * which runtime adapter (LangChain4j, Spring AI, Built-in, etc.) actually
 * runs.</p>
 */
@Name("org.atmosphere.ai.AgentTurn")
@Label("Atmosphere AI Agent Turn")
@Description("One iteration of an Atmosphere AI agent loop")
@Category({"Atmosphere", "AI"})
@StackTrace(false)
public final class AgentTurnEvent extends Event {

    @Label("Runtime")
    @Description("AgentRuntime name (langchain4j, spring-ai, built-in, ...)")
    public String runtime;

    @Label("Model")
    @Description("Model identifier reported on the request")
    public String model;

    @Label("Client ID")
    @Description("Pipeline client identifier (conversation/resource key)")
    public String clientId;

    @Label("Status")
    @Description("success / error / cancelled")
    public String status;

    @Label("Cache Hit")
    @Description("Whether the response was served from the response cache")
    public boolean cacheHit;

    @Label("Error Type")
    @Description("Optional classification when status=error")
    public String errorType;
}
