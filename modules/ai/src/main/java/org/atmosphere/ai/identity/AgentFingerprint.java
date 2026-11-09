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
package org.atmosphere.ai.identity;

import org.atmosphere.ai.facts.FactResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Identifies the agent configuration that served a turn: the runtime that
 * dispatched it, the model it asked for, and a version of its system prompt and
 * tool set. Audit records carry it so that what an agent did (its tool calls,
 * the governance decisions on its turns) can be attributed to the exact
 * configuration that did it, not only to the user it acted for.
 *
 * <h2>What the hashes cover</h2>
 * <ul>
 *   <li>{@link #systemPromptSha256} hashes the <em>stable</em> part of the
 *       system prompt the turn dispatched with: the developer prompt after scope
 *       hardening and guardrail rewrites, with a trailing grounded-facts block
 *       (which changes every turn) removed, so the same agent configuration
 *       keeps the same hash from turn to turn. The prompt itself is never
 *       recorded.</li>
 *   <li>{@link #toolSetSha256} hashes the sorted, distinct names of the tools
 *       offered to the model on the turn.</li>
 * </ul>
 * A component the turn did not have (no system prompt, no tools) is {@code null}
 * and is left out of {@link #asAttributes()}.
 *
 * @param runtime            name of the {@code AgentRuntime} that dispatched the turn
 * @param model              the model the turn requested
 * @param systemPromptSha256 lower-case hex SHA-256 of the stable system prompt
 * @param toolSetSha256      lower-case hex SHA-256 of the offered tool names
 */
public record AgentFingerprint(String runtime, String model,
                               String systemPromptSha256, String toolSetSha256) {

    /** Lineage attribute carrying {@link #runtime}. */
    public static final String RUNTIME_ATTRIBUTE = "ai.agent.runtime";
    /** Lineage attribute carrying {@link #model}. */
    public static final String MODEL_ATTRIBUTE = "ai.agent.model";
    /** Lineage attribute carrying {@link #systemPromptSha256}. */
    public static final String SYSTEM_PROMPT_ATTRIBUTE = "ai.agent.system_prompt_sha256";
    /** Lineage attribute carrying {@link #toolSetSha256}. */
    public static final String TOOL_SET_ATTRIBUTE = "ai.agent.tool_set_sha256";

    public AgentFingerprint {
        runtime = blankToNull(runtime);
        model = blankToNull(model);
    }

    /**
     * Fingerprint a turn from what it dispatched with.
     *
     * @param runtime      the dispatching runtime's name
     * @param model        the requested model
     * @param systemPrompt the system prompt the turn dispatched with
     * @param toolNames    names of the tools offered to the model
     */
    public static AgentFingerprint of(String runtime, String model, String systemPrompt,
                                      Collection<String> toolNames) {
        return new AgentFingerprint(runtime, model, systemPromptSha256(systemPrompt),
                toolSetSha256(toolNames));
    }

    /**
     * SHA-256 of the stable part of {@code systemPrompt} (a trailing
     * grounded-facts block removed), or {@code null} when nothing is left.
     */
    public static String systemPromptSha256(String systemPrompt) {
        var stable = FactResolver.FactBundle.stablePrefix(systemPrompt);
        return stable.isEmpty() ? null : sha256(stable);
    }

    /**
     * SHA-256 of the sorted, distinct, non-blank tool names joined by newlines,
     * or {@code null} when there are none.
     */
    public static String toolSetSha256(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return null;
        }
        var sorted = new TreeSet<String>();
        toolNames.stream()
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .forEach(sorted::add);
        return sorted.isEmpty() ? null : sha256(String.join("\n", sorted));
    }

    /** Whether {@code key} is one of the {@code ai.agent.*} names this record writes. */
    public static boolean isAttribute(String key) {
        return RUNTIME_ATTRIBUTE.equals(key) || MODEL_ATTRIBUTE.equals(key)
                || SYSTEM_PROMPT_ATTRIBUTE.equals(key) || TOOL_SET_ATTRIBUTE.equals(key);
    }

    /** The non-null components keyed by their {@code ai.agent.*} attribute names. */
    public Map<String, String> asAttributes() {
        var attributes = new LinkedHashMap<String, String>();
        putIfNotNull(attributes, RUNTIME_ATTRIBUTE, runtime);
        putIfNotNull(attributes, MODEL_ATTRIBUTE, model);
        putIfNotNull(attributes, SYSTEM_PROMPT_ATTRIBUTE, systemPromptSha256);
        putIfNotNull(attributes, TOOL_SET_ATTRIBUTE, toolSetSha256);
        return Map.copyOf(attributes);
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Lower-case hex SHA-256 of {@code value}'s UTF-8 bytes. */
    static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every Java platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
