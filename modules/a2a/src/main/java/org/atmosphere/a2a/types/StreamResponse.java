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

/**
 * Response chunk emitted on a streaming send/subscribe. Models the proto
 * {@code oneof payload {Task; Message; TaskStatusUpdateEvent;
 * TaskArtifactUpdateEvent}} — exactly one field is populated per chunk.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamResponse(
    Task task,
    Message message,
    TaskStatusUpdateEvent statusUpdate,
    TaskArtifactUpdateEvent artifactUpdate
) {
    public static StreamResponse of(Task task) {
        return new StreamResponse(task, null, null, null);
    }

    public static StreamResponse of(Message message) {
        return new StreamResponse(null, message, null, null);
    }

    public static StreamResponse of(TaskStatusUpdateEvent statusUpdate) {
        return new StreamResponse(null, null, statusUpdate, null);
    }

    public static StreamResponse of(TaskArtifactUpdateEvent artifactUpdate) {
        return new StreamResponse(null, null, null, artifactUpdate);
    }
}
