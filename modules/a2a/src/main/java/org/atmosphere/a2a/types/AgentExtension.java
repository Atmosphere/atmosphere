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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Protocol extension declared by an agent. Identified by a URI; the
 * {@code required} flag tells clients whether they must understand the
 * extension to interoperate. {@code params} is an open struct for
 * extension-specific configuration.
 *
 * <p>Atmosphere-specific extensions:</p>
 * <ul>
 *   <li>{@code https://atmosphere.async-io.org/extensions/guardrails/v1} —
 *       human-readable guardrails surfaced under {@code params.guardrails}.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExtension(
    String uri,
    String description,
    boolean required,
    Map<String, Object> params
) {
    public static final String GUARDRAILS_URI =
            "https://atmosphere.async-io.org/extensions/guardrails/v1";

    public AgentExtension {
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
