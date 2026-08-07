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
package org.atmosphere.nativeimage;

import java.util.List;

/**
 * Renders {@link NativeImageMetadata} as GraalVM's {@code reachability-metadata.json}.
 *
 * <p>The output is meant to be committed under
 * {@code META-INF/native-image/<group>/<artifact>/} in a shipped jar, where
 * GraalVM picks it up automatically. That is what makes the SPI portable rather
 * than Spring- and Quarkus-shaped: an application with neither integration
 * module — a plain servlet container, embedded Jetty — still gets every
 * reflective type the framework needs, with nothing to configure.</p>
 *
 * <p>Written by hand rather than with a JSON library because
 * {@code atmosphere-runtime} treats Jackson as optional, and a metadata
 * generator that only works when an optional dependency happens to be present
 * would defeat the purpose. The shape is small and fixed; the escaping below
 * covers what class and resource names can legally contain.</p>
 */
public final class NativeImageMetadataWriter {

    private NativeImageMetadataWriter() {
    }

    /**
     * Render metadata as GraalVM reachability JSON.
     *
     * <p>Every type is registered with all declared constructors, methods and
     * fields — matching what the Spring and Quarkus paths already request. These
     * types are selected by configuration and constructed reflectively, so the
     * framework cannot know which member it will need; narrowing the categories
     * would trade image size for exactly the class of silent runtime failure
     * this metadata exists to prevent.</p>
     *
     * @param metadata the merged provider output
     * @param comment  provenance line recorded in the file
     * @return pretty-printed JSON with a trailing newline
     */
    public static String render(NativeImageMetadata metadata, String comment) {
        var out = new StringBuilder(4096);
        out.append("{\n");
        out.append("  \"comment\": \"").append(escape(comment)).append("\",\n");

        out.append("  \"reflection\": [\n");
        appendReflection(out, metadata.reflectiveTypes());
        out.append("  ],\n");

        out.append("  \"resources\": [\n");
        appendResources(out, metadata.resourcePatterns());
        out.append("  ]\n");

        out.append("}\n");
        return out.toString();
    }

    private static void appendReflection(StringBuilder out, List<String> types) {
        for (int i = 0; i < types.size(); i++) {
            out.append("    {\n");
            out.append("      \"type\": \"").append(escape(types.get(i))).append("\",\n");
            out.append("      \"allDeclaredConstructors\": true,\n");
            out.append("      \"allDeclaredMethods\": true,\n");
            out.append("      \"allDeclaredFields\": true\n");
            out.append(i < types.size() - 1 ? "    },\n" : "    }\n");
        }
    }

    private static void appendResources(StringBuilder out, List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            out.append("    { \"glob\": \"").append(escape(patterns.get(i))).append("\" }");
            out.append(i < patterns.size() - 1 ? ",\n" : "\n");
        }
    }

    /** Escape the characters a class or resource name could legally carry. */
    private static String escape(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (var c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
