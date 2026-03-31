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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface representing the content part of an A2A message or artifact.
 * Permitted implementations are {@link TextPart} for plain text, {@link FilePart}
 * for file references, and {@link DataPart} for arbitrary structured data.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Part.TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = Part.FilePart.class, name = "file"),
    @JsonSubTypes.Type(value = Part.DataPart.class, name = "data")
})
public sealed interface Part {

    /** A content part carrying plain text and optional metadata. */
    record TextPart(String text, Map<String, Object> metadata) implements Part {
        public TextPart(String text) {
            this(text, Map.of());
        }
    }

    /** A content part referencing a file by URI or inline bytes, with an associated MIME type. */
    record FilePart(String name, String mimeType, String uri,
                    byte[] bytes, Map<String, Object> metadata) implements Part {
        public FilePart(String name, String mimeType, String uri) {
            this(name, mimeType, uri, null, Map.of());
        }
    }

    /** A content part carrying arbitrary structured data as a key-value map. */
    record DataPart(Map<String, Object> data, Map<String, Object> metadata) implements Part {
        public DataPart {
            data = data != null ? Map.copyOf(data) : Map.of();
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }
}
