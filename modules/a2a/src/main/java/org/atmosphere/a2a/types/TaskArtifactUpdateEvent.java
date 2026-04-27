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
 * Event emitted when a task produces or extends an {@link Artifact}. v1.0.0
 * adds {@code contextId}, {@code append} (whether to concatenate to an existing
 * artifact with the same id), {@code lastChunk} (final chunk marker), and a
 * free-form {@code metadata} struct.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskArtifactUpdateEvent(
    String taskId,
    String contextId,
    Artifact artifact,
    Boolean append,
    Boolean lastChunk,
    Map<String, Object> metadata
) {
    public TaskArtifactUpdateEvent {
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }

    public TaskArtifactUpdateEvent(String taskId, String contextId, Artifact artifact) {
        this(taskId, contextId, artifact, null, null, null);
    }
}
