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

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Map;

/**
 * Deserializes an A2A {@link Part} accepting either the v1.0.0 spec shape
 * (one of {@code text}, {@code raw}, {@code url}, {@code data} as a top-level
 * field, plus optional {@code metadata}, {@code filename}, {@code mediaType})
 * or the pre-1.0 polymorphic envelope ({@code {"type":"text",...}} or
 * {@code {"kind":"text",...}}).
 *
 * <p>Pre-1.0 file shape ({@code mimeType}, {@code uri}, {@code bytes}) is
 * normalized to v1.0.0 ({@code mediaType}, {@code url}, {@code raw}). The
 * legacy {@code TextPart}/{@code FilePart}/{@code DataPart} subtype
 * distinction is collapsed because v1.0.0 unified them.</p>
 */
public final class PartDeserializer extends ValueDeserializer<Part> {

    @Override
    public Part deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode root = ctxt.readTree(parser);
        if (!root.isObject()) {
            throw MismatchedInputException.from(parser, Part.class,
                    "A2A Part must be a JSON object, got " + root.getNodeType());
        }

        var legacyDiscriminator = root.has("type") ? root.get("type")
                : root.has("kind") ? root.get("kind")
                : null;

        Map<String, Object> metadata = readMap(root.get("metadata"), ctxt);
        String filename = stringOrNull(root.get("filename"));
        if (filename == null) {
            filename = stringOrNull(root.get("name"));
        }
        String mediaType = stringOrNull(root.get("mediaType"));
        if (mediaType == null) {
            mediaType = stringOrNull(root.get("mimeType"));
        }

        if (legacyDiscriminator != null && legacyDiscriminator.isString()) {
            return fromLegacy(parser, root, ctxt, legacyDiscriminator.asString(),
                    metadata, filename, mediaType);
        }

        if (root.has("text")) {
            return new Part(stringOrEmpty(root.get("text")), null, null, null,
                    metadata, filename, mediaType);
        }
        if (root.has("data")) {
            return new Part(null, null, null, readMap(root.get("data"), ctxt),
                    metadata, filename, mediaType);
        }
        if (root.has("raw")) {
            return new Part(null, ctxt.readTreeAsValue(root.get("raw"), byte[].class),
                    null, null, metadata, filename, mediaType);
        }
        if (root.has("url")) {
            return new Part(null, null, stringOrNull(root.get("url")), null,
                    metadata, filename, mediaType);
        }
        if (root.has("uri")) {
            return new Part(null, null, stringOrNull(root.get("uri")), null,
                    metadata, filename, mediaType);
        }
        throw MismatchedInputException.from(parser, Part.class,
                "A2A Part is missing content — expected one of text|raw|url|data");
    }

    private Part fromLegacy(JsonParser parser, JsonNode root, DeserializationContext ctxt,
                            String kind, Map<String, Object> metadata,
                            String filename, String mediaType) {
        return switch (kind) {
            case "text" -> new Part(stringOrEmpty(root.get("text")), null, null, null,
                    metadata, filename, mediaType);
            case "data" -> new Part(null, null, null, readMap(root.get("data"), ctxt),
                    metadata, filename, mediaType);
            case "file" -> {
                byte[] raw = root.has("bytes")
                        ? ctxt.readTreeAsValue(root.get("bytes"), byte[].class) : null;
                String url = stringOrNull(root.get("url"));
                if (url == null) {
                    url = stringOrNull(root.get("uri"));
                }
                yield new Part(null, raw, url, null, metadata, filename, mediaType);
            }
            default -> throw MismatchedInputException.from(parser, Part.class,
                    "Unknown legacy A2A Part kind: '" + kind + "'");
        };
    }

    private static String stringOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String stringOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(JsonNode node, DeserializationContext ctxt) {
        if (node == null || node.isNull()) {
            return null;
        }
        return ctxt.readTreeAsValue(node, Map.class);
    }
}
