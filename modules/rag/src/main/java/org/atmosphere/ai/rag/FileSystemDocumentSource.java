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
package org.atmosphere.ai.rag;

import org.atmosphere.ai.ContextProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link DocumentSource} that loads text files from a directory tree. Zero-dep
 * (JDK NIO only). Each matching file becomes one document whose {@code source}
 * is its path relative to the root.
 *
 * <p><strong>Boundary safety (Invariant #4):</strong> every candidate path is
 * resolved to its real path and verified to stay within the root, so a symlink
 * or {@code ..} escape pointing outside the directory is rejected rather than
 * read.</p>
 */
public final class FileSystemDocumentSource implements DocumentSource {

    /** Default extensions (lowercase, no dot) treated as text documents. */
    public static final Set<String> DEFAULT_EXTENSIONS = Set.of("md", "markdown", "txt", "text");

    private final Path root;
    private final Set<String> extensions;
    private final boolean recursive;

    /** Recursively load {@link #DEFAULT_EXTENSIONS} files under {@code root}. */
    public FileSystemDocumentSource(Path root) {
        this(root, DEFAULT_EXTENSIONS, true);
    }

    public FileSystemDocumentSource(Path root, Set<String> extensions, boolean recursive) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
        this.extensions = extensions == null || extensions.isEmpty()
                ? DEFAULT_EXTENSIONS
                : extensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        this.recursive = recursive;
    }

    @Override
    public List<ContextProvider.Document> load() {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a readable directory: " + root);
        }
        // Compare against the root's REAL path: on some platforms the root
        // itself is reached through a symlink (e.g. macOS /var -> /private/var),
        // so the boundary check must canonicalize both sides.
        final Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve document root " + root, e);
        }
        var docs = new ArrayList<ContextProvider.Document>();
        try (Stream<Path> walk = recursive ? Files.walk(realRoot) : Files.list(realRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(this::matchesExtension)
                    .filter(path -> isWithinRoot(realRoot, path))
                    .sorted()
                    .forEach(path -> docs.add(toDocument(realRoot, path)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list documents under " + root, e);
        }
        return List.copyOf(docs);
    }

    @Override
    public String name() {
        return "filesystem:" + root;
    }

    private boolean matchesExtension(Path path) {
        var file = path.getFileName().toString().toLowerCase(Locale.ROOT);
        var dot = file.lastIndexOf('.');
        return dot >= 0 && extensions.contains(file.substring(dot + 1));
    }

    private static boolean isWithinRoot(Path realRoot, Path path) {
        try {
            // Real path resolves any symlink target; a link pointing outside the
            // root resolves to a path that is no longer under it and is rejected.
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            // Unreadable / broken symlink — exclude rather than risk an escape.
            return false;
        }
    }

    private static ContextProvider.Document toDocument(Path realRoot, Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + path, e);
        }
        var relative = realRoot.relativize(path).toString();
        return new ContextProvider.Document(content, relative, 1.0,
                Map.of("source_path", relative, "source_type", "filesystem"));
    }
}
