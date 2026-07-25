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
package org.atmosphere.ai.prompt;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed {@code prompt:} system-prompt reference.
 *
 * <p>Syntax (mirrors the existing {@code skill:} prefix convention):</p>
 * <ul>
 *   <li>{@code prompt:support-agent} — bare name: rollout selection when
 *       {@code atmosphere.ai.prompt.rollout.support-agent} is configured,
 *       otherwise the latest version</li>
 *   <li>{@code prompt:support-agent@v2} — pinned version</li>
 *   <li>{@code prompt:support-agent@latest} — explicit latest</li>
 * </ul>
 *
 * <p>Names are restricted to {@code [A-Za-z0-9][A-Za-z0-9._-]*} and versions to
 * {@code v<digits>} so a reference can never smuggle path separators or
 * traversal segments into the file-backed registry (Correctness Invariant #4).</p>
 *
 * @param name    the prompt name
 * @param version the pinned version ({@code v<digits>}), or {@code null} when
 *                the reference is bare or explicitly {@code @latest}
 * @param bare    {@code true} when no {@code @version} suffix was given at all
 *                (bare references are eligible for rollout selection;
 *                {@code @latest} is not)
 */
public record PromptReference(String name, String version, boolean bare) {

    /** The reference prefix that routes a system-prompt value through the registry. */
    public static final String PREFIX = "prompt:";

    /** Version alias resolving to the highest available version. */
    public static final String LATEST = "latest";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("v\\d{1,9}");

    /**
     * Returns whether the given system-prompt value is a managed-prompt
     * reference (starts with {@link #PREFIX}).
     *
     * @param value the raw annotation value (may be {@code null})
     * @return {@code true} when the value must resolve through the registry
     */
    public static boolean isReference(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Parses and validates a {@code prompt:} reference.
     *
     * @param value the raw value, e.g. {@code "prompt:support-agent@v2"}
     * @return the parsed reference
     * @throws IllegalArgumentException if the value is not a reference or the
     *         name/version fails validation
     */
    public static PromptReference parse(String value) {
        if (!isReference(value)) {
            throw new IllegalArgumentException(
                    "Not a managed prompt reference (missing '" + PREFIX + "' prefix): " + value);
        }
        var body = value.substring(PREFIX.length());
        var at = body.indexOf('@');
        var name = at < 0 ? body : body.substring(0, at);
        var version = at < 0 ? null : body.substring(at + 1);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid prompt name '" + name + "' in reference '" + value
                            + "' (allowed: [A-Za-z0-9][A-Za-z0-9._-]*)");
        }
        if (version == null) {
            return new PromptReference(name, null, true);
        }
        if (LATEST.equals(version)) {
            return new PromptReference(name, null, false);
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Invalid prompt version '" + version + "' in reference '" + value
                            + "' (allowed: v<digits> or 'latest')");
        }
        return new PromptReference(name, version, false);
    }

    /**
     * Returns the pinned version, if any.
     *
     * @return the version, or empty for bare / {@code @latest} references
     */
    public Optional<String> pinnedVersion() {
        return Optional.ofNullable(version);
    }
}
