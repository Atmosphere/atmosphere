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
import java.util.Map;
import java.util.UUID;

/**
 * Reusable output produced by a skill. v1.0.0 added the {@code extensions}
 * URI list; the rest of the shape is unchanged from the pre-1.0 record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(
    String artifactId,
    String name,
    String description,
    List<Part> parts,
    Map<String, Object> metadata,
    List<String> extensions
) {
    public Artifact {
        parts = parts != null ? List.copyOf(parts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : null;
        extensions = extensions != null ? List.copyOf(extensions) : null;
    }

    public Artifact(String artifactId, String name, String description, List<Part> parts) {
        this(artifactId, name, description, parts, null, null);
    }

    public static Artifact text(String text) {
        return new Artifact(UUID.randomUUID().toString(), null, null,
                List.of(Part.text(text)), null, null);
    }

    public static Artifact named(String name, String description, List<Part> parts) {
        return new Artifact(UUID.randomUUID().toString(), name, description, parts, null, null);
    }
}
