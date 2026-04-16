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
package org.atmosphere.ai.extensibility;

/**
 * A compact descriptor for a tool — just what the {@link ToolIndex} needs to
 * rank and select tools for a query. Heavier state (parameter schemas, tool
 * handlers) stays with the tool's owning registry; {@code ToolDescriptor}
 * is cheap to index and serialize.
 *
 * @param id           stable identifier, e.g. {@code "github.createPr"}
 * @param description  human-readable description (searched against queries)
 * @param tags         tag terms that boost relevance, e.g.
 *                     {@code ["git", "code"]}
 */
public record ToolDescriptor(String id, String description, java.util.List<String> tags) {

    public ToolDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (description == null) {
            description = "";
        }
        tags = tags != null ? java.util.List.copyOf(tags) : java.util.List.of();
    }
}
