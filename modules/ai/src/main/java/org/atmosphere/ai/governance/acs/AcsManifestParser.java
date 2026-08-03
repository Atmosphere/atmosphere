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
package org.atmosphere.ai.governance.acs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a YAML document rooted at
 * {@code agent_control_specification_version} into an {@link AcsManifest}.
 * Invoked by {@code YamlPolicyParser} when it detects the ACS root key;
 * malformed documents throw {@link IOException} so policy loading fails
 * closed at startup per the {@code PolicyParser} contract.
 */
public final class AcsManifestParser {

    private AcsManifestParser() {
    }

    /** Root key that identifies an ACS manifest document. */
    public static final String ROOT_KEY = "agent_control_specification_version";

    /**
     * Parse an already-loaded YAML root mapping into a manifest.
     *
     * @param source diagnostic source id, e.g. {@code yaml:/etc/policies/manifest.yaml}
     * @param root   the YAML document root
     * @return the parsed manifest
     * @throws IOException on any structural violation
     */
    public static AcsManifest parse(String source, Map<?, ?> root) throws IOException {
        var version = string(root.get(ROOT_KEY));
        if (version == null || version.isBlank()) {
            throw new IOException(ROOT_KEY + " must be a non-blank string in " + source);
        }

        String name = null;
        var description = "";
        if (root.get("metadata") instanceof Map<?, ?> metadata) {
            name = string(metadata.get("name"));
            var desc = string(metadata.get("description"));
            description = desc == null ? "" : desc;
        }
        if (name == null || name.isBlank()) {
            throw new IOException("metadata.name is required in ACS manifest " + source);
        }

        var extendsRefs = new ArrayList<String>();
        var extendsRaw = root.get("extends");
        if (extendsRaw != null) {
            if (!(extendsRaw instanceof List<?> list)) {
                throw new IOException("'extends' must be a sequence in " + source);
            }
            for (var entry : list) {
                var ref = string(entry);
                if (ref == null || ref.isBlank()) {
                    throw new IOException("'extends' entries must be non-blank strings in " + source);
                }
                extendsRefs.add(ref);
            }
        }

        var policies = parsePolicies(source, root.get("policies"));
        if (policies.isEmpty()) {
            throw new IOException("ACS manifest " + source + " declares no policies");
        }

        var points = parseInterventionPoints(source, root.get("intervention_points"), policies);
        var tools = parseTools(source, root.get("tools"));
        var annotators = parseAnnotators(source, root.get("annotators"));

        return new AcsManifest(version, name, description, extendsRefs,
                policies, points, tools, annotators);
    }

    private static Map<String, AcsManifest.PolicyRef> parsePolicies(String source, Object raw)
            throws IOException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("'policies' must be a mapping of id -> declaration in " + source);
        }
        var result = new LinkedHashMap<String, AcsManifest.PolicyRef>();
        for (var entry : map.entrySet()) {
            var id = string(entry.getKey());
            if (id == null || id.isBlank()) {
                throw new IOException("policy ids must be non-blank strings in " + source);
            }
            if (!(entry.getValue() instanceof Map<?, ?> decl)) {
                throw new IOException("policy '" + id + "' must be a mapping in " + source);
            }
            var type = string(decl.get("type"));
            if (type == null || type.isBlank()) {
                throw new IOException("policy '" + id + "' is missing 'type' in " + source);
            }
            result.put(id, new AcsManifest.PolicyRef(type,
                    string(decl.get("bundle")), string(decl.get("query"))));
        }
        return result;
    }

    private static Map<String, AcsManifest.InterventionPoint> parseInterventionPoints(
            String source, Object raw, Map<String, AcsManifest.PolicyRef> policies)
            throws IOException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("'intervention_points' must be a mapping in " + source);
        }
        var result = new LinkedHashMap<String, AcsManifest.InterventionPoint>();
        for (var entry : map.entrySet()) {
            var pointName = string(entry.getKey());
            if (pointName == null || pointName.isBlank()) {
                throw new IOException("intervention point names must be non-blank in " + source);
            }
            if (!(entry.getValue() instanceof Map<?, ?> decl)) {
                throw new IOException("intervention point '" + pointName
                        + "' must be a mapping in " + source);
            }
            String policyId = null;
            String query = null;
            if (decl.get("policy") instanceof Map<?, ?> policy) {
                policyId = string(policy.get("id"));
                query = string(policy.get("query"));
            }
            if (policyId == null || policyId.isBlank()) {
                throw new IOException("intervention point '" + pointName
                        + "' is missing policy.id in " + source);
            }
            if (!policies.containsKey(policyId)) {
                throw new IOException("intervention point '" + pointName
                        + "' binds unknown policy '" + policyId + "' in " + source);
            }
            var annotations = new LinkedHashMap<String, String>();
            if (decl.get("annotations") instanceof Map<?, ?> annRaw) {
                for (var ann : annRaw.entrySet()) {
                    var annName = string(ann.getKey());
                    String from = null;
                    if (ann.getValue() instanceof Map<?, ?> annDecl) {
                        from = string(annDecl.get("from"));
                    }
                    if (annName == null || from == null) {
                        throw new IOException("annotation on intervention point '" + pointName
                                + "' must declare 'from' in " + source);
                    }
                    annotations.put(annName, from);
                }
            }
            result.put(pointName, new AcsManifest.InterventionPoint(
                    string(decl.get("policy_target")),
                    string(decl.get("policy_target_kind")),
                    string(decl.get("tool_name_from")),
                    policyId, query, annotations));
        }
        return result;
    }

    private static Map<String, AcsManifest.Tool> parseTools(String source, Object raw)
            throws IOException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("'tools' must be a mapping in " + source);
        }
        var result = new LinkedHashMap<String, AcsManifest.Tool>();
        for (var entry : map.entrySet()) {
            var toolName = string(entry.getKey());
            if (toolName == null || !(entry.getValue() instanceof Map<?, ?> decl)) {
                throw new IOException("tool declarations must be mappings in " + source);
            }
            result.put(toolName, new AcsManifest.Tool(
                    string(decl.get("type")),
                    string(decl.get("id")),
                    stringList(source, "tools." + toolName + ".clearance", decl.get("clearance")),
                    stringList(source, "tools." + toolName + ".security_labels",
                            decl.get("security_labels"))));
        }
        return result;
    }

    private static Map<String, AcsManifest.Annotator> parseAnnotators(String source, Object raw)
            throws IOException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("'annotators' must be a mapping in " + source);
        }
        var result = new LinkedHashMap<String, AcsManifest.Annotator>();
        for (var entry : map.entrySet()) {
            var annName = string(entry.getKey());
            if (annName == null || !(entry.getValue() instanceof Map<?, ?> decl)) {
                throw new IOException("annotator declarations must be mappings in " + source);
            }
            var type = string(decl.get("type"));
            if (type == null || type.isBlank()) {
                throw new IOException("annotator '" + annName + "' is missing 'type' in " + source);
            }
            result.put(annName, new AcsManifest.Annotator(type,
                    string(decl.get("endpoint")),
                    string(decl.get("model")),
                    string(decl.get("classifier"))));
        }
        return result;
    }

    /**
     * Upstream accepts a scalar or a sequence for label-valued fields
     * (the tutorial writes {@code clearance: internal}, the contract fixture
     * a sequence) — normalize both to a list.
     */
    private static List<String> stringList(String source, String field, Object raw)
            throws IOException {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            var result = new ArrayList<String>(list.size());
            for (var entry : list) {
                var value = string(entry);
                if (value == null) {
                    throw new IOException(field + " entries must be strings in " + source);
                }
                result.add(value);
            }
            return result;
        }
        var single = string(raw);
        if (single == null) {
            throw new IOException(field + " must be a string or sequence of strings in " + source);
        }
        return List.of(single);
    }

    private static String string(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            default -> null;
        };
    }
}
