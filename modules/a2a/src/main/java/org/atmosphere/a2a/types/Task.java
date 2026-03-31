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

/**
 * Immutable snapshot of an A2A task, including its current status, accumulated messages,
 * produced artifacts, and metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
    String id,
    @JsonProperty("contextId") String contextId,
    TaskStatus status,
    List<Message> messages,
    List<Artifact> artifacts,
    Map<String, Object> metadata
) {
    public Task {
        messages = messages != null ? List.copyOf(messages) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** The current status of a task, pairing a {@link TaskState} with an optional message. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStatus(TaskState state, String message) {
    }
}
