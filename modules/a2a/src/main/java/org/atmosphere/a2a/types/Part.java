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

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * Sealed interface representing the content part of an A2A message or artifact.
 * Permitted implementations are {@link TextPart} for plain text, {@link FilePart}
 * for file references, and {@link DataPart} for arbitrary structured data.
 *
 * <p>Wire discriminator is {@code "type"} on emission; on parse we accept both
 * {@code "type"} and {@code "kind"} — the latter is what the current A2A spec
 * emits, while earlier drafts (and some servers, including ours) still use
 * {@code "type"}. Custom {@link PartDeserializer} reads the JSON tree, picks
 * the concrete subtype from whichever discriminator is present, and dispatches
 * directly — skipping Jackson's polymorphic machinery so it never reaches
 * for a hard-coded {@code type} field and fails when only {@code kind} is
 * available.</p>
 */
@JsonDeserialize(using = PartDeserializer.class)
public sealed interface Part {

    /**
     * Discriminator emitted as {@code "type"} on the wire. Each concrete
     * subtype returns its canonical name so the output stays spec-compliant
     * (the server emits {@code type}; the {@link PartDeserializer} accepts
     * either {@code type} or {@code kind} on input).
     */
    @JsonProperty("type")
    String type();

    /** A content part carrying plain text and optional metadata. */
    record TextPart(String text, Map<String, Object> metadata) implements Part {
        public TextPart(String text) {
            this(text, Map.of());
        }

        @Override
        public String type() {
            return "text";
        }
    }

    /** A content part referencing a file by URI or inline bytes, with an associated MIME type. */
    record FilePart(String name, String mimeType, String uri,
                    byte[] bytes, Map<String, Object> metadata) implements Part {
        public FilePart(String name, String mimeType, String uri) {
            this(name, mimeType, uri, null, Map.of());
        }

        @Override
        public String type() {
            return "file";
        }
    }

    /** A content part carrying arbitrary structured data as a key-value map. */
    record DataPart(Map<String, Object> data, Map<String, Object> metadata) implements Part {
        public DataPart {
            data = data != null ? Map.copyOf(data) : Map.of();
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        @Override
        public String type() {
            return "data";
        }
    }
}
