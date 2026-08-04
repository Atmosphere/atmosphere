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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.rag.pgvector.PgVectorContextProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives {@link PgVectorContextProvider} against a real pgvector database.
 *
 * <p>Every RAG connector in the tree ships with a unit test that mocks the
 * transport: the pgvector one string-matches the SQL it expects, and the Qdrant
 * one asserts against a sample response this repository wrote itself. Those
 * prove the provider builds the query it was written to build — they cannot
 * prove Postgres accepts it, that {@code CAST(? AS vector)} parses, that the
 * {@code <=>} operator resolves, or that rows come back in distance order. This
 * test puts a live pgvector container behind the provider so the protocol claim
 * has evidence behind it.</p>
 *
 * <p><b>Docker policy.</b> Skipped locally when Docker is unavailable, but a
 * hard failure under {@code CI} — a connector test that silently skips in the
 * one environment that gates merges would be worse than no test, because the
 * matrix would read green while nothing had spoken to a database.</p>
 */
@Tag("ai")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PgVectorBackendIntegrationTest {

    /** Ships pgvector preinstalled; plain postgres images do not have the extension. */
    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres");

    private static final String TABLE = "rag_documents";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    @BeforeAll
    public void setUp() throws Exception {
        var dockerAvailable = isDockerAvailable();
        if (!dockerAvailable && isCi()) {
            throw new IllegalStateException(
                    "Docker is required for the pgvector backend test and CI is set. "
                            + "Skipping here would leave the vector-store protocol claim "
                            + "unproven while the lane reports green.");
        }
        assumeTrue(dockerAvailable, "Docker unavailable — skipping pgvector backend test");

        postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
                .withDatabaseName("atmosphere_rag")
                .withUsername("atmosphere")
                .withPassword("atmosphere");
        postgres.start();

        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        seed();
    }

    @AfterAll
    public void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Three documents whose embeddings are unit vectors on distinct axes, so
     * cosine distance ordering is exact rather than approximate — the assertion
     * is about the provider and the SQL, not about an embedding model.
     */
    private void seed() throws Exception {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            stmt.execute("CREATE TABLE " + TABLE + " ("
                    + "id serial PRIMARY KEY, "
                    + "content text NOT NULL, "
                    + "source text, "
                    + "embedding vector(3))");
            stmt.execute("INSERT INTO " + TABLE + " (content, source, embedding) VALUES "
                    + "('the cat sat on the mat', 'cats.md', '[1,0,0]'), "
                    + "('kubernetes pod scheduling', 'k8s.md', '[0,1,0]'), "
                    + "('sourdough starter hydration', 'bread.md', '[0,0,1]')");
        }
    }

    private PgVectorContextProvider provider(float[] queryVector) {
        EmbeddingRuntime embeddings = new FixedEmbeddingRuntime(queryVector);
        return PgVectorContextProvider.builder(dataSource, embeddings)
                .table(TABLE)
                .embeddingColumn("embedding")
                .contentColumn("content")
                .sourceColumn("source")
                .build();
    }

    @Test
    void retrievesTheNearestDocumentFromALivePgvectorDatabase() {
        // Query vector points at the "cats" axis.
        var docs = provider(new float[]{1f, 0f, 0f}).retrieve("tell me about cats", 1);

        assertEquals(1, docs.size(), "maxResults must bound the result set");
        assertEquals("the cat sat on the mat", docs.get(0).content(),
                "the nearest vector must come back — this is the assertion a mocked "
                        + "transport cannot make");
        assertEquals("cats.md", docs.get(0).source(),
                "the source column must be projected through");
    }

    @Test
    void ordersResultsByVectorDistanceNotInsertionOrder() {
        // Points at the third axis; the bread row is inserted last, so insertion
        // order and distance order disagree — which is the point.
        var docs = provider(new float[]{0f, 0f, 1f}).retrieve("baking", 3);

        assertEquals(3, docs.size());
        assertEquals("sourdough starter hydration", docs.get(0).content(),
                "the closest vector must sort first; if the ORDER BY were dropped "
                        + "this would return the cats row");
    }

    @Test
    void anEmptyEmbeddingShortCircuitsWithoutQuerying() {
        var docs = provider(new float[0]).retrieve("anything", 5);

        assertTrue(docs.isEmpty(),
                "an embedding runtime returning nothing must not produce a malformed "
                        + "vector literal for Postgres to reject");
    }

    @Test
    void theSeededSchemaActuallyUsesPgvector() throws Exception {
        // Guards the test from passing against a plain text column, which would
        // make every assertion above meaningless.
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT atttypid::regtype::text FROM pg_attribute "
                             + "WHERE attrelid = '" + TABLE + "'::regclass "
                             + "AND attname = 'embedding'")) {
            assertTrue(rs.next(), "the embedding column must exist");
            assertEquals("vector", rs.getString(1),
                    "the column must be a real pgvector type, not text");
        }
    }

    @Test
    void dockerPolicyIsHardFailureUnderCi() {
        // The policy itself, asserted rather than described: if this ever became
        // a silent skip in CI the lane would go green with nothing proven.
        assertFalse(isCi() && !isDockerAvailable(),
                "under CI, Docker must be present — the setUp guard throws rather "
                        + "than skipping, and this pins that intent");
    }

    private static boolean isCi() {
        var ci = System.getenv("CI");
        return ci != null && !ci.isBlank() && !"false".equalsIgnoreCase(ci.trim());
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns a fixed vector, so ordering is a property of the SQL, not a model. */
    private record FixedEmbeddingRuntime(float[] vector) implements EmbeddingRuntime {
        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            return vector;
        }
    }
}
