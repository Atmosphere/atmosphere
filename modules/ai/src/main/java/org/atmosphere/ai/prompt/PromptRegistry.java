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

import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * SPI for versioned prompt storage. The shipped implementation is
 * {@link FilePromptRegistry} (classpath + optional disk-override tier);
 * alternative backends (database, config service) register via
 * {@link java.util.ServiceLoader} and are picked up by {@link PromptResolver}.
 *
 * <p>A registry stores raw prompt text per {@code (name, version)}. Version
 * strings are {@code v<digits>} ({@code v1}, {@code v2}, ...); "latest" is the
 * numerically highest available version. Rollout selection between versions is
 * config-driven and shared across backends via the {@code selectVersion}
 * default method.</p>
 */
public interface PromptRegistry {

    /**
     * Returns the raw text of an exact prompt version.
     *
     * @param name    the prompt name ({@code [A-Za-z0-9][A-Za-z0-9._-]*})
     * @param version the version ({@code v<digits>})
     * @return the prompt text, or empty when the version does not exist
     * @throws IllegalStateException if the stored content fails integrity
     *         verification (fail closed — tampered prompts never resolve)
     */
    Optional<String> content(String name, String version);

    /**
     * Lists the available versions of a prompt, ascending numerically.
     *
     * @param name the prompt name
     * @return the versions, empty when the prompt is unknown
     */
    List<String> versions(String name);

    /**
     * Returns the numerically highest available version.
     *
     * @param name the prompt name
     * @return the latest version, or empty when the prompt is unknown
     */
    default Optional<String> latestVersion(String name) {
        var all = versions(name);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    /**
     * Selects the version a rollout unit should receive: the configured
     * deterministic percentage split
     * ({@code atmosphere.ai.prompt.rollout.<name>}, see {@link PromptRollout})
     * when present, otherwise the latest version. The same {@code unitId}
     * always receives the same version for the same configuration.
     *
     * @param name   the prompt name
     * @param unitId the rollout unit — a user id, or the endpoint identity at
     *               the annotation resolution seam
     * @return the selected version, or empty when no version exists and no
     *         rollout is configured
     * @throws IllegalStateException if the rollout spec is malformed (fail closed)
     */
    default Optional<String> selectVersion(String name, String unitId) {
        Optional<SequencedMap<String, Integer>> weights = PromptRollout.configuredWeights(name);
        if (weights.isPresent()) {
            return Optional.of(PromptRollout.select(name, weights.get(), unitId));
        }
        return latestVersion(name);
    }
}
