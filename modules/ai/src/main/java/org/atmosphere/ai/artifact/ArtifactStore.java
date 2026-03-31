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
package org.atmosphere.ai.artifact;

import java.util.List;
import java.util.Optional;

/**
 * SPI for artifact persistence. Agents store and retrieve binary artifacts
 * (reports, images, code files) through this interface.
 *
 * <p>Thread-safe: multiple agents may store/load concurrently.</p>
 *
 * @see Artifact
 * @see InMemoryArtifactStore
 */
public interface ArtifactStore {

    /**
     * Save an artifact. If an artifact with the same ID already exists in the
     * namespace, a new version is created.
     *
     * @param artifact the artifact to save
     * @return the saved artifact (with version number assigned)
     */
    Artifact save(Artifact artifact);

    /**
     * Load the latest version of an artifact by ID within a namespace.
     *
     * @param namespace  the namespace
     * @param artifactId the artifact ID
     * @return the artifact, or empty if not found
     */
    Optional<Artifact> load(String namespace, String artifactId);

    /**
     * Load a specific version of an artifact.
     *
     * @param namespace  the namespace
     * @param artifactId the artifact ID
     * @param version    the version number
     * @return the artifact, or empty if not found
     */
    Optional<Artifact> load(String namespace, String artifactId, int version);

    /**
     * List all artifacts in a namespace (latest version of each).
     *
     * @param namespace the namespace
     * @return list of artifacts, ordered by creation time (newest first)
     */
    List<Artifact> list(String namespace);

    /**
     * Delete an artifact and all its versions.
     *
     * @param namespace  the namespace
     * @param artifactId the artifact ID
     * @return true if the artifact existed and was deleted
     */
    boolean delete(String namespace, String artifactId);

    /**
     * Delete all artifacts in a namespace.
     *
     * @param namespace the namespace
     */
    void deleteAll(String namespace);
}
