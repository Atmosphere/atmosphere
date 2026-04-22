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
 * Deserializes an A2A {@link Part} JSON envelope while accepting the discriminator
 * under either {@code type} (earlier A2A drafts and our own emitter) or
 * {@code kind} (current A2A spec). Dispatches directly to the concrete
 * subtype instead of going through Jackson's polymorphic machinery, so there's
 * no loop back into this deserializer when the reader is annotated with
 * {@code @JsonDeserialize(using = PartDeserializer.class)}.
 *
 * <p>Without this bridge, a client sending the spec-compliant
 * {@code {"kind":"text","text":"hi"}} silently lost its payload because Jackson
 * couldn't find the discriminator — the server parsed an empty
 * {@link Part.TextPart} and the user message was dropped.</p>
 */
public final class PartDeserializer extends ValueDeserializer<Part> {

    private static final String TYPE = "type";
    private static final String KIND = "kind";

    @Override
    public Part deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode root = ctxt.readTree(parser);
        if (!root.isObject()) {
            throw MismatchedInputException.from(parser, Part.class,
                    "A2A Part must be a JSON object, got " + root.getNodeType());
        }

        JsonNode discriminator = root.has(TYPE) ? root.get(TYPE)
                : root.has(KIND) ? root.get(KIND)
                : null;
        if (discriminator == null || !discriminator.isString()) {
            throw MismatchedInputException.from(parser, Part.class,
                    "A2A Part is missing the discriminator — expected 'type' or 'kind' "
                            + "with one of \"text\", \"file\", \"data\"");
        }
        String kind = discriminator.asString();
        // Build the concrete record by reading fields from the JsonNode directly
        // rather than delegating to ctxt.readTreeAsValue — that path would
        // loop back through @JsonDeserialize(using = PartDeserializer.class)
        // because the annotation is inherited from the sealed interface.
        return switch (kind) {
            case "text" -> new Part.TextPart(
                    stringOrEmpty(root.get("text")),
                    readMap(root.get("metadata"), ctxt));
            case "file" -> new Part.FilePart(
                    stringOrNull(root.get("name")),
                    stringOrNull(root.get("mimeType")),
                    stringOrNull(root.get("uri")),
                    root.has("bytes") ? ctxt.readTreeAsValue(root.get("bytes"), byte[].class) : null,
                    readMap(root.get("metadata"), ctxt));
            case "data" -> new Part.DataPart(
                    readMap(root.get("data"), ctxt),
                    readMap(root.get("metadata"), ctxt));
            default -> throw MismatchedInputException.from(parser, Part.class,
                    "Unknown A2A Part kind: '" + kind + "' — expected text | file | data");
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
            return Map.of();
        }
        return ctxt.readTreeAsValue(node, Map.class);
    }
}
