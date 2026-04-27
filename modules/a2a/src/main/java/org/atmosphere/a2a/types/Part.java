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
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * Content part of an A2A {@link Message} or {@link Artifact}. Collapsed in
 * v1.0.0 from three polymorphic subtypes ({@code TextPart} / {@code FilePart} /
 * {@code DataPart}) into a single record carrying a {@code oneof} of
 * {@link #text}, {@link #raw} bytes, a {@link #url}, or structured
 * {@link #data}, plus the shared {@link #metadata}, {@link #filename}, and
 * {@link #mediaType} fields.
 *
 * <p>On the wire exactly one of the four content fields is populated; the
 * record permits all to be {@code null} for record-default convenience but the
 * static factory methods enforce the invariant.</p>
 *
 * <p>The deserializer accepts the pre-1.0 polymorphic shape
 * ({@code {"type":"text",...}} or {@code {"kind":"text",...}}) for migration —
 * see {@link PartDeserializer}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = PartDeserializer.class)
public record Part(
    String text,
    byte[] raw,
    String url,
    Map<String, Object> data,
    Map<String, Object> metadata,
    String filename,
    String mediaType
) {
    public Part {
        data = data != null ? Map.copyOf(data) : null;
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }

    public static Part text(String text) {
        return new Part(text, null, null, null, null, null, null);
    }

    public static Part text(String text, String mediaType) {
        return new Part(text, null, null, null, null, null, mediaType);
    }

    public static Part raw(byte[] raw, String filename, String mediaType) {
        return new Part(null, raw, null, null, null, filename, mediaType);
    }

    public static Part url(String url, String filename, String mediaType) {
        return new Part(null, null, url, null, null, filename, mediaType);
    }

    public static Part data(Map<String, Object> data) {
        return new Part(null, null, null, data, null, null, null);
    }
}
