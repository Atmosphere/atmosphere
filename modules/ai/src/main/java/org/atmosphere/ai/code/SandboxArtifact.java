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
package org.atmosphere.ai.code;

import java.util.Arrays;
import java.util.Objects;

/**
 * A binary artifact produced by a code-execution round — most commonly a
 * screenshot, but also generated files (CSVs, PDFs) the model wrote to the
 * sandbox workspace and asked to surface.
 *
 * <p>The sandbox layer stays decoupled from {@code org.atmosphere.ai.Content};
 * the code-exec tool maps a {@code SandboxArtifact} onto a
 * {@code Content.Image}/{@code Content.File} frame when streaming it to the
 * browser.</p>
 *
 * @param name     a short, human-readable label (e.g. {@code "screenshot-1.png"})
 * @param mimeType the artifact's media type (e.g. {@code "image/png"})
 * @param data     the raw bytes; defensively copied
 */
public record SandboxArtifact(String name, String mimeType, byte[] data) {

    public SandboxArtifact {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be null or blank");
        }
        data = data == null ? new byte[0] : data.clone();
    }

    /** Defensive copy on read so the record stays effectively immutable. */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** Size of the artifact in bytes. */
    public int size() {
        return data.length;
    }

    // Records with array components need value-based equals/hashCode to behave
    // as immutable value carriers; the default identity-based array semantics
    // would make two artifacts with identical bytes compare unequal.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SandboxArtifact other
                && name.equals(other.name)
                && mimeType.equals(other.mimeType)
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, mimeType, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "SandboxArtifact[name=" + name + ", mimeType=" + mimeType
                + ", size=" + data.length + "B]";
    }
}
