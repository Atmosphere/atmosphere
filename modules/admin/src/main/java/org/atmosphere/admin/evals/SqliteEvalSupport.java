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
package org.atmosphere.admin.evals;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Shared plumbing for the SQLite-backed eval stores: connection opening (with
 * a busy timeout so the run store and dataset store can share one database
 * file), idempotent close, and a dependency-free key/value text codec.
 *
 * <p>The codec URL-encodes each component (Correctness Invariant #4 — encode
 * at the persistence boundary) so keys, tags, and values containing
 * {@code =}, {@code &}, or newlines round-trip exactly without pulling a JSON
 * mapper into the admin module.</p>
 */
final class SqliteEvalSupport {

    private SqliteEvalSupport() {
    }

    static Connection open(Path dbPath, String description) {
        var abs = dbPath.toAbsolutePath();
        var parent = abs.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create directory: " + parent, e);
            }
        }
        try {
            var connection = DriverManager.getConnection("jdbc:sqlite:" + abs);
            connection.setAutoCommit(true);
            try (var stmt = connection.createStatement()) {
                // The run store and the dataset store may share one db file;
                // wait briefly on a writer instead of failing with SQLITE_BUSY.
                stmt.execute("PRAGMA busy_timeout = 5000");
            }
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite " + description + ": " + abs, e);
        }
    }

    static void closeQuietly(Connection connection, Logger logger) {
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warn("Failed to close SQLite connection: {}", e.toString());
        }
    }

    /** Encode string pairs as {@code k=v&k=v} with URL-encoded components. */
    static String encodePairs(Map<String, String> pairs) {
        var joiner = new StringJoiner("&");
        for (var entry : pairs.entrySet()) {
            joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    /** Decode {@link #encodePairs} output; blank input yields an empty map. */
    static Map<String, String> decodePairs(String encoded) {
        var result = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        for (var pair : encoded.split("&")) {
            var separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            result.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    /** Encode a list of values with URL-encoded components joined by {@code &}. */
    static String encodeList(List<String> values) {
        var joiner = new StringJoiner("&");
        for (var value : values) {
            joiner.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    /** Decode {@link #encodeList} output; blank input yields an empty list. */
    static List<String> decodeList(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return Arrays.stream(encoded.split("&"))
                .filter(part -> !part.isBlank())
                .map(part -> URLDecoder.decode(part, StandardCharsets.UTF_8))
                .toList();
    }
}
