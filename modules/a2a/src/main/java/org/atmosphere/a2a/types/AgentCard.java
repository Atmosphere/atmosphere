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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
    String name,
    String description,
    String url,
    String version,
    String provider,
    String documentationUrl,
    AgentCapabilities capabilities,
    List<Skill> skills,
    Map<String, Object> securitySchemes,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    List<String> guardrails
) {
    public AgentCard {
        skills = skills != null ? List.copyOf(skills) : List.of();
        securitySchemes = securitySchemes != null ? Map.copyOf(securitySchemes) : Map.of();
        defaultInputModes = defaultInputModes != null ? List.copyOf(defaultInputModes) : List.of("text");
        defaultOutputModes = defaultOutputModes != null ? List.copyOf(defaultOutputModes) : List.of("text");
        guardrails = guardrails != null ? List.copyOf(guardrails) : null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCapabilities(
        boolean streaming,
        @JsonProperty("pushNotifications") boolean pushNotifications,
        @JsonProperty("stateTransitionHistory") boolean stateTransitionHistory
    ) {
    }
}
