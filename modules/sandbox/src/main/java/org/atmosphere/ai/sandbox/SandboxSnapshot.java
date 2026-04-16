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
package org.atmosphere.ai.sandbox;

import java.time.Instant;

/**
 * Reference to a sandbox filesystem snapshot. The {@code reference} is
 * implementation-specific (Docker image id, btrfs snapshot name, etc.)
 * and is opaque to callers — pass it back through
 * {@link Sandbox#exec(java.util.List, java.time.Duration)} paths or
 * implementation-specific restore entry points.
 */
public record SandboxSnapshot(
        String id,
        String reference,
        Instant createdAt) {

    public SandboxSnapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
