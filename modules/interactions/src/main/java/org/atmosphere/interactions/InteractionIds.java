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
package org.atmosphere.interactions;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minting and boundary validation for interaction identifiers.
 *
 * <p>Ids are server-minted ({@link #mint()}), but {@code get}/{@code cancel}/
 * {@code delete}/{@code continue} accept caller-supplied ids. Every inbound id
 * passes through {@link #isValid(String)} before it is used as a store key, so
 * a malformed id is rejected at the boundary with a 400-class outcome rather
 * than reaching path-traversal-adjacent code — separators, {@code ..}, and
 * control characters are all refused (Correctness Invariant #4 — Boundary
 * Safety).</p>
 */
public final class InteractionIds {

    /** Server-minted ids carry this prefix; the regex also admits external ids. */
    public static final String PREFIX = "int-";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private InteractionIds() {
    }

    /** Generate a fresh, valid interaction id. */
    public static String mint() {
        return PREFIX + UUID.randomUUID();
    }

    /**
     * Whether {@code id} is a syntactically valid interaction id: 1–128 chars
     * of {@code [A-Za-z0-9_-]} only. Rejects {@code null}, empty, path
     * separators, {@code ..}, whitespace, and control characters.
     */
    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /**
     * Return {@code id} if valid, otherwise throw {@link IllegalArgumentException}
     * — the boundary check callers use when a malformed id must surface as a
     * 400 rather than a 500.
     *
     * @throws IllegalArgumentException if {@code id} is not {@link #isValid(String) valid}
     */
    public static String requireValid(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Invalid interaction id");
        }
        return id;
    }
}
