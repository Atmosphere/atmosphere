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

import org.atmosphere.ai.EmbeddingRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorContextProviderTest {

    @Test
    void retrieveReturnsEmptyForBlankQuery() {
        var provider = PgVectorContextProvider
                .builder(mock(DataSource.class), new FakeEmbedding())
                .build();

        assertTrue(provider.retrieve("  ", 4).isEmpty());
        assertTrue(provider.retrieve(null, 4).isEmpty());
        assertTrue(provider.retrieve("ok", 0).isEmpty());
        assertTrue(provider.retrieve("ok", -1).isEmpty());
    }

    @Test
    void invalidIdentifierRejectedAtBuild() {
        var ds = mock(DataSource.class);
        var emb = new FakeEmbedding();

        assertThrows(IllegalArgumentException.class,
                () -> PgVectorContextProvider.builder(ds, emb).table("docs; DROP TABLE docs;--").build());
        assertThrows(IllegalArgumentException.class,
                () -> PgVectorContextProvider.builder(ds, emb).embeddingColumn("emb space").build());
        assertThrows(IllegalArgumentException.class,
                () -> PgVectorContextProvider.builder(ds, emb).contentColumn("1content").build());
        assertThrows(IllegalArgumentException.class,
                () -> PgVectorContextProvider.builder(ds, emb).distanceOperator(") OR 1=1; --").build());
    }

    @Test
    void retrieveBuildsExpectedSqlAndMapsRows() throws SQLException {
        var ds = mock(DataSource.class);
        var conn = mock(Connection.class);
        var stmt = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("first chunk", "second chunk");
        when(rs.getString(2)).thenReturn("a.md", "b.md");
        when(rs.getDouble(3)).thenReturn(0.1, 0.4);

        var provider = PgVectorContextProvider
                .builder(ds, new FakeEmbedding())
                .table("kb_documents")
                .embeddingColumn("embedding")
                .contentColumn("content")
                .sourceColumn("source")
                .build();

        var hits = provider.retrieve("hello", 4);

        assertEquals(2, hits.size());
        assertEquals("first chunk", hits.get(0).content());
        assertEquals("a.md", hits.get(0).source());
        assertEquals(0.9, hits.get(0).score(), 1e-9,
                "score should be 1 - distance so higher means more relevant");
        assertEquals(0.6, hits.get(1).score(), 1e-9);

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCaptor.capture());
        var sql = sqlCaptor.getValue();
        assertTrue(sql.contains("FROM kb_documents"), sql);
        assertTrue(sql.contains("embedding <=> CAST(? AS vector)"), sql);
        assertTrue(sql.contains("LIMIT ?"), sql);

        verify(stmt).setString(eq(1), anyString());
        verify(stmt).setInt(2, 4);
    }

    @Test
    void retrieveSkipsSourceColumnWhenUnset() throws SQLException {
        var ds = mock(DataSource.class);
        var conn = mock(Connection.class);
        var stmt = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("text");
        when(rs.getDouble(2)).thenReturn(0.0);

        var provider = PgVectorContextProvider
                .builder(ds, new FakeEmbedding())
                .sourceColumn(null)
                .build();

        var hits = provider.retrieve("q", 1);
        assertEquals(1, hits.size());
        assertNull(hits.get(0).source(), "source should be null when sourceColumn is unset");

        verify(rs, times(0)).getString(2);
    }

    @Test
    void retrieveSwallowsSqlExceptionAndReturnsEmpty() throws SQLException {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

        var provider = PgVectorContextProvider
                .builder(ds, new FakeEmbedding())
                .build();
        assertTrue(provider.retrieve("anything", 3).isEmpty(),
                "SQL failures must not propagate to the caller; the RAG layer treats retrieve "
                        + "as best-effort.");
    }

    @Test
    void encodeVectorMatchesPgvectorTextFormat() {
        assertEquals("[1.0,2.5,-0.3]",
                PgVectorContextProvider.encodeVector(new float[]{1.0f, 2.5f, -0.3f}));
        assertEquals("[]", PgVectorContextProvider.encodeVector(new float[0]));
    }

    @Test
    void isAvailableReturnsFalseWhenEmbeddingUnavailable() {
        var ds = mock(DataSource.class);
        var emb = new FakeEmbedding(false);
        var provider = PgVectorContextProvider.builder(ds, emb).build();
        assertEquals(false, provider.isAvailable());
    }

    private static final class FakeEmbedding implements EmbeddingRuntime {
        private final boolean available;

        FakeEmbedding() {
            this(true);
        }

        FakeEmbedding(boolean available) {
            this.available = available;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }
}
