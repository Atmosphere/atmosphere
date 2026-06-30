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
package org.atmosphere.ai.resume;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Single source of truth for {@link EffectJournal} idempotency-key derivation, so
 * the BuiltIn round seam and the cross-runtime tool seam compute identical keys
 * for identical effects.
 *
 * <p>Keys are SHA-256 hex digests over null-separated parts (the separator makes
 * the parts unambiguous — {@code ("a","bc")} and {@code ("ab","c")} hash
 * differently). Tool-call keys fold in a <em>canonical</em> (recursively
 * sorted-key) JSON rendering of the arguments so the same logical call hashes
 * identically regardless of map iteration order (Boundary Safety, Correctness
 * Invariant #4).</p>
 *
 * @since 4.0
 */
public final class EffectKeys {

    /**
     * Canonicalizing mapper: {@code ORDER_MAP_ENTRIES_BY_KEYS} sorts every map's
     * entries (recursively) so equal argument maps serialize byte-identically.
     */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private EffectKeys() {
    }

    /** Key for the once-per-run input seed effect. */
    public static String runInput(String runId) {
        return sha256Hex(runId, "input");
    }

    /**
     * Key for an LLM round, derived positionally from the tool-loop round index.
     * Stable under full replay because replay re-walks rounds {@code 0, 1, 2, …}
     * in order.
     */
    public static String llmRound(String runId, int toolRound) {
        return sha256Hex(runId, "llm", Integer.toString(toolRound));
    }

    /**
     * Key for a tool call, content-addressed by tool name + canonical args +
     * an occurrence ordinal. The ordinal disambiguates identical repeated calls
     * (e.g. {@code delete_row(7)} twice → ordinals 0, 1) so they never collide;
     * the caller advances it once per call in both first-drive and replay.
     */
    public static String toolCall(String runId, String toolName,
                                  Map<String, Object> args, int occurrence) {
        return sha256Hex(runId, "tool", toolName, canonicalJson(args), Integer.toString(occurrence));
    }

    /**
     * Recursively sorted-key JSON rendering of {@code args}; the same logical
     * arguments produce the same string regardless of key order. A {@code null}
     * map renders as {@code "null"}.
     */
    public static String canonicalJson(Map<String, Object> args) {
        if (args == null) {
            return "null";
        }
        try {
            return CANONICAL.writeValueAsString(args);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Tool arguments are not JSON-serializable", e);
        }
    }

    /**
     * SHA-256 hex over the given parts, each part written as its UTF-8 bytes
     * followed by a {@code 0x00} separator so concatenations are unambiguous. A
     * {@code null} part contributes only its separator.
     */
    public static String sha256Hex(String... parts) {
        var digest = newDigest();
        for (var part : parts) {
            if (part != null) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
