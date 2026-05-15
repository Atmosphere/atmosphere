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
package org.atmosphere.ai.rag.pgvector;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.EmbeddingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Direct {@link ContextProvider} for Postgres + pgvector, with no Spring AI or
 * LangChain4j dependency. Embeds the user query through an
 * {@link EmbeddingRuntime}, then issues a single prepared statement using the
 * pgvector {@code <=>} cosine-distance operator to retrieve the top-{@code k}
 * matching rows.
 *
 * <p>The provider validates table and column identifiers against a strict
 * pattern before splicing them into the prepared statement (column / table
 * names are not parameterizable in JDBC). This is the Boundary Safety
 * requirement of Correctness Invariant #4 — any caller-supplied identifier
 * that does not match {@code [A-Za-z_][A-Za-z0-9_]*} is rejected at
 * construction time.</p>
 *
 * <p>Construction example:</p>
 * <pre>{@code
 * var provider = PgVectorContextProvider.builder(dataSource, embeddingRuntime)
 *     .table("documents")
 *     .embeddingColumn("embedding")
 *     .contentColumn("content")
 *     .sourceColumn("source")
 *     .build();
 *
 * var hits = provider.retrieve("What is Atmosphere?", 4);
 * }</pre>
 *
 * <p>Wire compatibility: pgvector exposes the cosine-distance operator as
 * {@code <=>}; this provider also accepts an alternative operator name (e.g.
 * {@code <->} for L2) through {@link Builder#distanceOperator(String)}.</p>
 */
public final class PgVectorContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorContextProvider.class);

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern DISTANCE_OP = Pattern.compile("^<[=\\-#]>$");

    private final DataSource dataSource;
    private final EmbeddingRuntime embeddingRuntime;
    private final String table;
    private final String embeddingColumn;
    private final String contentColumn;
    private final String sourceColumn;
    private final String distanceOperator;
    private final int queryTimeoutSeconds;

    private PgVectorContextProvider(Builder b) {
        this.dataSource = Objects.requireNonNull(b.dataSource, "dataSource");
        this.embeddingRuntime = Objects.requireNonNull(b.embeddingRuntime, "embeddingRuntime");
        this.table = requireIdentifier(b.table, "table");
        this.embeddingColumn = requireIdentifier(b.embeddingColumn, "embeddingColumn");
        this.contentColumn = requireIdentifier(b.contentColumn, "contentColumn");
        this.sourceColumn = b.sourceColumn == null ? null
                : requireIdentifier(b.sourceColumn, "sourceColumn");
        this.distanceOperator = requireDistanceOperator(b.distanceOperator);
        this.queryTimeoutSeconds = b.queryTimeoutSeconds;
    }

    public static Builder builder(DataSource dataSource, EmbeddingRuntime embeddingRuntime) {
        return new Builder(dataSource, embeddingRuntime);
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults <= 0) {
            return List.of();
        }
        float[] vector = embeddingRuntime.embed(query);
        if (vector == null || vector.length == 0) {
            return List.of();
        }

        var sql = "SELECT " + contentColumn
                + (sourceColumn != null ? ", " + sourceColumn : "")
                + ", " + embeddingColumn + " " + distanceOperator + " CAST(? AS vector) AS _distance"
                + " FROM " + table
                + " ORDER BY _distance ASC"
                + " LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, encodeVector(vector));
            stmt.setInt(2, maxResults);
            if (queryTimeoutSeconds > 0) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                var out = new ArrayList<Document>();
                while (rs.next()) {
                    var content = rs.getString(1);
                    var source = sourceColumn != null ? rs.getString(2) : null;
                    var distance = rs.getDouble(sourceColumn != null ? 3 : 2);
                    // pgvector returns cosine distance in [0, 2]; convert to a
                    // similarity score in [-1, 1] so larger = more relevant,
                    // matching the contract InMemoryContextProvider already
                    // honors. Callers that need raw distance can shadow this
                    // by subclassing.
                    var score = 1.0 - distance;
                    out.add(new Document(content, source, score));
                }
                return List.copyOf(out);
            }
        } catch (SQLException e) {
            logger.warn("pgvector retrieve failed (table={}, query='{}'): {}",
                    table, abbreviate(query), e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        if (!embeddingRuntime.isAvailable()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1);
        } catch (SQLException e) {
            logger.debug("pgvector availability probe failed: {}", e.getMessage());
            return false;
        }
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must match [A-Za-z_][A-Za-z0-9_]* (was: " + value + ")");
        }
        return value;
    }

    private static String requireDistanceOperator(String value) {
        if (value == null) {
            return "<=>";
        }
        if (!DISTANCE_OP.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "distanceOperator must be one of <=>, <->, <#> (was: " + value + ")");
        }
        return value;
    }

    static String encodeVector(float[] vector) {
        var sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    /** Builder mirrors the immutability-by-construction pattern used by the other RAG providers. */
    public static final class Builder {
        private final DataSource dataSource;
        private final EmbeddingRuntime embeddingRuntime;
        private String table = "documents";
        private String embeddingColumn = "embedding";
        private String contentColumn = "content";
        private String sourceColumn = "source";
        private String distanceOperator = "<=>";
        private int queryTimeoutSeconds = 30;

        private Builder(DataSource dataSource, EmbeddingRuntime embeddingRuntime) {
            this.dataSource = dataSource;
            this.embeddingRuntime = embeddingRuntime;
        }

        public Builder table(String value) {
            this.table = value;
            return this;
        }

        public Builder embeddingColumn(String value) {
            this.embeddingColumn = value;
            return this;
        }

        public Builder contentColumn(String value) {
            this.contentColumn = value;
            return this;
        }

        /** Pass {@code null} to suppress source-column reads. */
        public Builder sourceColumn(String value) {
            this.sourceColumn = value;
            return this;
        }

        /** {@code <=>} cosine (default), {@code <->} L2, or {@code <#>} inner product. */
        public Builder distanceOperator(String value) {
            this.distanceOperator = value;
            return this;
        }

        /** JDBC query timeout in seconds; {@code 0} disables the timeout. */
        public Builder queryTimeoutSeconds(int value) {
            this.queryTimeoutSeconds = value;
            return this;
        }

        public PgVectorContextProvider build() {
            return new PgVectorContextProvider(this);
        }
    }
}
