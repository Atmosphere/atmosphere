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
 * Single A2A message exchanged between user and agent. Aligned with the
 * v1.0.0 schema: {@code role} is the {@link Role} enum (was a free String
 * pre-1.0), and {@code extensions} / {@code referenceTaskIds} were added.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
    String messageId,
    String contextId,
    String taskId,
    Role role,
    List<Part> parts,
    Map<String, Object> metadata,
    List<String> extensions,
    List<String> referenceTaskIds
) {
    public Message {
        parts = parts != null ? List.copyOf(parts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : null;
        extensions = extensions != null ? List.copyOf(extensions) : null;
        referenceTaskIds = referenceTaskIds != null ? List.copyOf(referenceTaskIds) : null;
    }

    public static Message user(String text) {
        return new Message(UUID.randomUUID().toString(), null, null,
                Role.USER, List.of(Part.text(text)), null, null, null);
    }

    public static Message agent(String text) {
        return new Message(UUID.randomUUID().toString(), null, null,
                Role.AGENT, List.of(Part.text(text)), null, null, null);
    }
}
