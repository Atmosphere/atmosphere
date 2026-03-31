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
package org.atmosphere.ai.artifact;

import java.time.Instant;
import java.util.Map;

/**
 * An artifact produced by or consumed by an AI agent. Artifacts are
 * versioned, typed binary objects that persist across agent runs.
 *
 * <p>Use cases: agent-generated reports, images, code files, CSV exports,
 * and any content shared between coordinated agents.</p>
 *
 * @param id        unique artifact identifier
 * @param namespace grouping key (e.g., session ID, agent name, user ID)
 * @param fileName  human-readable file name (e.g., "report.pdf")
 * @param mimeType  MIME type (e.g., "application/pdf", "image/png")
 * @param data      the binary content
 * @param version   version number (monotonically increasing per artifact ID)
 * @param metadata  arbitrary key-value metadata
 * @param createdAt creation timestamp
 */
public record Artifact(
        String id,
        String namespace,
        String fileName,
        String mimeType,
        byte[] data,
        int version,
        Map<String, String> metadata,
        Instant createdAt
) {
    public Artifact {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be null or blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be null or blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be null or blank");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Convenience: data size in bytes. */
    public int size() {
        return data.length;
    }
}
