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
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(
    @JsonProperty("artifactId") String artifactId,
    String name,
    String description,
    List<Part> parts,
    Map<String, Object> metadata
) {
    public Artifact {
        parts = parts != null ? List.copyOf(parts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static Artifact text(String text) {
        return new Artifact(UUID.randomUUID().toString(), null, null,
                List.of(new Part.TextPart(text)), Map.of());
    }

    public static Artifact named(String name, String description, List<Part> parts) {
        return new Artifact(UUID.randomUUID().toString(), name, description, parts, Map.of());
    }
}
