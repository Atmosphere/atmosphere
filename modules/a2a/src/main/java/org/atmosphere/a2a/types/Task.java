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

/**
 * Server-managed task. v1.0.0 renamed the message log field {@code messages}
 * to {@code history} and lifted {@link TaskStatus} to a top-level type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
    String id,
    String contextId,
    TaskStatus status,
    List<Artifact> artifacts,
    List<Message> history,
    Map<String, Object> metadata
) {
    public Task {
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        history = history != null ? List.copyOf(history) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }
}
