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

import java.util.List;

/**
 * Spec-aligned skill descriptor surfaced on an {@link AgentCard}. Replaces the
 * pre-1.0 {@code Skill} record. v1.0.0 adds {@code examples},
 * {@code inputModes}, {@code outputModes}, and {@code securityRequirements};
 * the pre-1.0 {@code inputSchema} / {@code outputSchema} maps were never part
 * of the spec and have been dropped.
 *
 * <p>Note: the simple name collides with
 * {@link org.atmosphere.a2a.annotation.AgentSkill}; the package qualifier
 * disambiguates them — the annotation marks a Java handler method, this record
 * is the over-the-wire descriptor.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSkill(
    String id,
    String name,
    String description,
    List<String> tags,
    List<String> examples,
    List<String> inputModes,
    List<String> outputModes,
    List<SecurityRequirement> securityRequirements
) {
    public AgentSkill {
        tags = tags != null ? List.copyOf(tags) : List.of();
        examples = examples != null ? List.copyOf(examples) : null;
        inputModes = inputModes != null ? List.copyOf(inputModes) : null;
        outputModes = outputModes != null ? List.copyOf(outputModes) : null;
        securityRequirements = securityRequirements != null
                ? List.copyOf(securityRequirements) : null;
    }

    public AgentSkill(String id, String name, String description, List<String> tags) {
        this(id, name, description, tags, null, null, null, null);
    }
}
