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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default in-memory implementation of {@link ArtifactStore}.
 * Suitable for development and testing. Data does not survive JVM restart.
 *
 * <p>Thread-safe via {@link ReentrantLock} per namespace.</p>
 */
public class InMemoryArtifactStore implements ArtifactStore {

    private final ConcurrentMap<String, ConcurrentMap<String, List<Artifact>>> store =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Artifact save(Artifact artifact) {
        var lock = locks.computeIfAbsent(artifact.namespace(), k -> new ReentrantLock());
        var nsStore = store.computeIfAbsent(artifact.namespace(), k -> new ConcurrentHashMap<>());
        lock.lock();
        try {
            var versions = nsStore.computeIfAbsent(artifact.id(), k -> new ArrayList<>());
            int nextVersion = versions.isEmpty() ? 1 : versions.getLast().version() + 1;
            var versioned = new Artifact(
                    artifact.id(), artifact.namespace(), artifact.fileName(),
                    artifact.mimeType(), artifact.data(), nextVersion,
                    artifact.metadata(), artifact.createdAt());
            versions.add(versioned);
            return versioned;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Artifact> load(String namespace, String artifactId) {
        var nsStore = store.get(namespace);
        if (nsStore == null) {
            return Optional.empty();
        }
        var lock = locks.get(namespace);
        if (lock == null) {
            return Optional.empty();
        }
        lock.lock();
        try {
            var versions = nsStore.get(artifactId);
            if (versions == null || versions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(versions.getLast());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Artifact> load(String namespace, String artifactId, int version) {
        var nsStore = store.get(namespace);
        if (nsStore == null) {
            return Optional.empty();
        }
        var lock = locks.get(namespace);
        if (lock == null) {
            return Optional.empty();
        }
        lock.lock();
        try {
            var versions = nsStore.get(artifactId);
            if (versions == null) {
                return Optional.empty();
            }
            return versions.stream()
                    .filter(a -> a.version() == version)
                    .findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Artifact> list(String namespace) {
        var nsStore = store.get(namespace);
        if (nsStore == null) {
            return List.of();
        }
        var lock = locks.get(namespace);
        if (lock == null) {
            return List.of();
        }
        lock.lock();
        try {
            return nsStore.values().stream()
                    .filter(versions -> !versions.isEmpty())
                    .map(List::getLast)
                    .sorted(Comparator.comparing(Artifact::createdAt).reversed())
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(String namespace, String artifactId) {
        var nsStore = store.get(namespace);
        if (nsStore == null) {
            return false;
        }
        var lock = locks.get(namespace);
        if (lock == null) {
            return false;
        }
        lock.lock();
        try {
            return nsStore.remove(artifactId) != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteAll(String namespace) {
        store.remove(namespace);
        locks.remove(namespace);
    }
}
