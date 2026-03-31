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
package org.atmosphere.ai.adk;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.atmosphere.ai.artifact.Artifact;
import org.atmosphere.ai.artifact.ArtifactStore;

import java.time.Instant;
import java.util.Map;

/**
 * Bridges Atmosphere {@link ArtifactStore} to ADK's {@link BaseArtifactService}.
 *
 * <p>Two directions:</p>
 * <ul>
 *   <li>{@link #toAdkService(ArtifactStore)} — wrap an Atmosphere store as an ADK service
 *       for use with {@code InMemoryRunner}</li>
 *   <li>{@link #toAtmosphereStore(BaseArtifactService)} — wrap an ADK service as an
 *       Atmosphere store for framework-agnostic access</li>
 * </ul>
 */
public final class AdkArtifactBridge {

    private AdkArtifactBridge() {
    }

    /**
     * Wrap an Atmosphere {@link ArtifactStore} as an ADK {@link BaseArtifactService}.
     * The resulting service can be registered with ADK's runner.
     *
     * @param store the Atmosphere artifact store
     * @return an ADK-compatible artifact service backed by the Atmosphere store
     */
    public static BaseArtifactService toAdkService(ArtifactStore store) {
        return new AtmosphereBackedArtifactService(store);
    }

    /**
     * Create a default ADK {@link InMemoryArtifactService}. Useful when no
     * custom Atmosphere store is configured but ADK still needs artifact support.
     *
     * @return a new in-memory ADK artifact service
     */
    public static BaseArtifactService defaultAdkService() {
        return new InMemoryArtifactService();
    }

    /**
     * ADK {@link BaseArtifactService} backed by Atmosphere's {@link ArtifactStore}.
     */
    private static final class AtmosphereBackedArtifactService implements BaseArtifactService {

        private final ArtifactStore store;

        AtmosphereBackedArtifactService(ArtifactStore store) {
            this.store = store;
        }

        @Override
        public Single<Integer> saveArtifact(String appName, String userId,
                                            String sessionId, String artifactName,
                                            Part part) {
            var namespace = appName + ":" + userId + ":" + sessionId;
            var data = part.text().map(String::getBytes).orElse(new byte[0]);
            var artifact = new Artifact(
                    artifactName, namespace, artifactName,
                    "application/octet-stream", data, 0,
                    Map.of("appName", appName, "userId", userId, "sessionId", sessionId),
                    Instant.now());
            var saved = store.save(artifact);
            return Single.just(saved.version());
        }

        @Override
        public Maybe<Part> loadArtifact(String appName, String userId,
                                        String sessionId, String artifactName,
                                        Integer version) {
            var namespace = appName + ":" + userId + ":" + sessionId;
            var result = version != null
                    ? store.load(namespace, artifactName, version)
                    : store.load(namespace, artifactName);
            return result
                    .map(a -> Maybe.just(Part.fromText(new String(a.data()))))
                    .orElse(Maybe.empty());
        }

        @Override
        public Single<ListArtifactsResponse> listArtifactKeys(String appName,
                                                               String userId,
                                                               String sessionId) {
            var namespace = appName + ":" + userId + ":" + sessionId;
            var artifacts = store.list(namespace);
            var keys = artifacts.stream()
                    .map(Artifact::id)
                    .toList();
            return Single.just(ListArtifactsResponse.builder()
                    .filenames(keys)
                    .build());
        }

        @Override
        public Completable deleteArtifact(String appName, String userId,
                                          String sessionId, String artifactName) {
            var namespace = appName + ":" + userId + ":" + sessionId;
            store.delete(namespace, artifactName);
            return Completable.complete();
        }

        @Override
        public Single<com.google.common.collect.ImmutableList<Integer>> listVersions(
                String appName, String userId, String sessionId, String artifactName) {
            // InMemoryArtifactStore doesn't expose version list directly,
            // but we can load and check
            var namespace = appName + ":" + userId + ":" + sessionId;
            var latest = store.load(namespace, artifactName);
            if (latest.isEmpty()) {
                return Single.just(com.google.common.collect.ImmutableList.of());
            }
            var versions = com.google.common.collect.ImmutableList.<Integer>builder();
            for (int v = 1; v <= latest.get().version(); v++) {
                versions.add(v);
            }
            return Single.just(versions.build());
        }
    }
}
