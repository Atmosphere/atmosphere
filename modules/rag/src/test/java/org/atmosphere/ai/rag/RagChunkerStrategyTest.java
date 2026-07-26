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
import org.atmosphere.ai.rag.RagChunker.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary behaviour of the structure-aware chunking strategies: what each one
 * cuts on, that the size bound always holds, what overlap means per strategy,
 * and that degenerate inputs (empty, whitespace, one huge unbreakable line) do
 * not lose the document or blow the bound.
 */
class RagChunkerStrategyTest {

    @AfterEach
    void clearInstalledStrategy() {
        RagChunker.setDefaultStrategy(null);
        System.clearProperty(RagChunker.STRATEGY_KEY);
    }

    private static ContextProvider.Document doc(String content) {
        return new ContextProvider.Document(content, "guide.md", 1.0);
    }

    // ── Strategy selection ──

    @Test
    void defaultStrategyIsFixedSoExistingCallersAreUnchanged() {
        assertEquals(Strategy.FIXED, RagChunker.defaultStrategy());
    }

    @Test
    void systemPropertySelectsTheStrategyForTheNoStrategyOverloads() {
        System.setProperty(RagChunker.STRATEGY_KEY, "sentence");
        assertEquals(Strategy.SENTENCE, RagChunker.defaultStrategy());

        System.setProperty(RagChunker.STRATEGY_KEY, "MarkDown");
        assertEquals(Strategy.MARKDOWN, RagChunker.defaultStrategy(), "parsing is case-insensitive");
    }

    @Test
    void installedStrategyWinsOverThePropertyAndNullReverts() {
        System.setProperty(RagChunker.STRATEGY_KEY, "sentence");
        RagChunker.setDefaultStrategy(Strategy.MARKDOWN);
        assertEquals(Strategy.MARKDOWN, RagChunker.defaultStrategy());

        RagChunker.setDefaultStrategy(null);
        assertEquals(Strategy.SENTENCE, RagChunker.defaultStrategy(),
                "clearing the install falls back to the property");
    }

    @Test
    void unknownStrategyValueDegradesToFixedRatherThanFailingIngestion() {
        assertEquals(Strategy.FIXED, Strategy.from("semantic-magic"));
        assertEquals(Strategy.FIXED, Strategy.from(""));
        assertEquals(Strategy.FIXED, Strategy.from(null));
    }

    @Test
    void configuredStrategyReachesTheNoStrategyOverloadCallSites() {
        // The production call sites (InMemoryContextProvider.fromSourceChunked /
        // fromClasspathChunked, application chunkAll calls) use the overloads
        // without an explicit strategy — this proves config actually steers them.
        var markdown = "# Alpha\nAlpha body text here.\n\n# Beta\nBeta body text here.\n";
        RagChunker.setDefaultStrategy(Strategy.MARKDOWN);

        var chunks = RagChunker.chunk(doc(markdown), 30, 5);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().anyMatch(c -> "Alpha".equals(c.metadata().get("chunk_heading"))),
                "the configured markdown strategy ran through the 3-arg overload");
    }

    // ── Markdown strategy ──

    @Test
    void markdownCutsOnHeadingsAndAttributesTheHeading() {
        var content = """
                # Transports
                WebSocket, SSE and long-polling are supported.

                # Broadcasters
                A Broadcaster fans a message out to many resources.

                # Handlers
                An AtmosphereHandler receives the request.
                """;

        var chunks = RagChunker.chunk(doc(content), 70, 10, Strategy.MARKDOWN);

        assertEquals(3, chunks.size(), "one chunk per heading section at this bound");
        assertEquals("Transports", chunks.get(0).metadata().get(RagChunker.HEADING_METADATA_KEY));
        assertEquals("Broadcasters", chunks.get(1).metadata().get(RagChunker.HEADING_METADATA_KEY));
        assertEquals("Handlers", chunks.get(2).metadata().get(RagChunker.HEADING_METADATA_KEY));
        assertTrue(chunks.get(0).content().startsWith("# Transports"),
                "the heading line stays with its section");
        assertTrue(chunks.get(1).content().contains("Broadcaster fans a message"));
    }

    @Test
    void markdownPacksSeveralSmallSectionsIntoOneChunkUpToTheBound() {
        var content = "# A\naaa\n\n# B\nbbb\n\n# C\nccc\n\n# D\n" + "d".repeat(200) + "\n";

        var chunks = RagChunker.chunk(doc(content), 60, 10, Strategy.MARKDOWN);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).content().contains("# A") && chunks.get(0).content().contains("# C"),
                "small adjacent sections share a chunk: " + chunks.get(0).content());
        assertMaxSize(chunks, 60);
    }

    @Test
    void markdownKeepsFencedCodeBlocksIntactEvenWithHashCommentsInside() {
        var content = """
                # Install
                Run the installer:

                ```bash
                # this is a shell comment, not a markdown heading
                ./mvnw install
                # another comment line
                ```

                # Verify
                Check the output.
                """;

        // 140 is above the Install section (131 chars) but below the whole
        // document, so the section is packed whole rather than windowed — the
        // fence behaviour under test, not the oversized-section fallback.
        var chunks = RagChunker.chunk(doc(content), 140, 20, Strategy.MARKDOWN);

        var installChunk = chunks.stream()
                .filter(c -> c.content().contains("./mvnw install"))
                .findFirst()
                .orElseThrow();
        assertTrue(installChunk.content().contains("# this is a shell comment"),
                "a '#' inside a fence must not cut the block: " + installChunk.content());
        assertTrue(installChunk.content().contains("# another comment line"));
        assertEquals("Install", installChunk.metadata().get(RagChunker.HEADING_METADATA_KEY));
    }

    @Test
    void markdownFallsBackToTheFixedWindowInsideAnOversizedSection() {
        var body = "word ".repeat(80);
        var content = "# Huge\n" + body;

        var chunks = RagChunker.chunk(doc(content), 100, 20, Strategy.MARKDOWN);

        assertTrue(chunks.size() > 1, "an oversized section is windowed, not emitted whole");
        assertMaxSize(chunks, 100);
        assertTrue(chunks.stream().allMatch(
                        c -> "Huge".equals(c.metadata().get(RagChunker.HEADING_METADATA_KEY))),
                "every window keeps its section's heading attribution");
    }

    @Test
    void markdownWithoutAnyHeadingStillRespectsTheBound() {
        var content = "no headings here at all. " .repeat(20);

        var chunks = RagChunker.chunk(doc(content), 90, 15, Strategy.MARKDOWN);

        assertTrue(chunks.size() > 1);
        assertMaxSize(chunks, 90);
        assertTrue(chunks.stream().noneMatch(c -> c.metadata().containsKey(RagChunker.HEADING_METADATA_KEY)),
                "no heading metadata is invented when the document has no headings");
    }

    // ── Sentence strategy ──

    @Test
    void sentenceCutsOnSentenceBoundariesNotMidWord() {
        var content = "Atmosphere supports WebSocket. It also supports SSE. "
                + "Long-polling is the fallback. Streaming works everywhere.";

        var chunks = RagChunker.chunk(doc(content), 60, 0, Strategy.SENTENCE);

        assertTrue(chunks.size() > 1);
        assertMaxSize(chunks, 60);
        for (var chunk : chunks) {
            assertTrue(chunk.content().endsWith(".") || chunk.content().endsWith("!")
                            || chunk.content().endsWith("?"),
                    "chunks end on a sentence terminator: '" + chunk.content() + "'");
        }
    }

    @Test
    void sentenceOverlapCarriesTheTrailingSentenceForward() {
        var content = "One alpha here. Two beta here. Three gamma here. Four delta here.";

        var withOverlap = RagChunker.chunk(doc(content), 34, 20, Strategy.SENTENCE);
        var withoutOverlap = RagChunker.chunk(doc(content), 34, 0, Strategy.SENTENCE);

        assertMaxSize(withOverlap, 34);
        assertMaxSize(withoutOverlap, 34);
        assertTrue(withOverlap.get(1).content().startsWith("Two beta here."),
                "the previous chunk's trailing sentence opens the next chunk: "
                        + withOverlap.get(1).content());
        assertFalse(withoutOverlap.get(1).content().startsWith("Two beta here."),
                "with overlap 0 the next chunk starts at a fresh sentence: "
                        + withoutOverlap.get(1).content());
    }

    @Test
    void sentenceFallsBackToTheFixedWindowForOneOversizedSentence() {
        // No sentence terminator anywhere: BreakIterator yields one span far
        // larger than the bound, which must still be windowed.
        var content = "alpha ".repeat(60).trim();

        var chunks = RagChunker.chunk(doc(content), 80, 10, Strategy.SENTENCE);

        assertTrue(chunks.size() > 1);
        assertMaxSize(chunks, 80);
    }

    @Test
    void sentenceOverlapIsClampedByMaxCharsAndAlwaysAdvances() {
        // overlapChars (25) is large relative to a sentence (13), so more than
        // one trailing sentence qualifies on tail length alone. The size bound
        // must clamp the carry to one, and every chunk must start strictly
        // after the previous one so the walk terminates at the document end.
        var content = "Aaa bbb ccc. Ddd eee fff. Ggg hhh iii. Jjj kkk lll. Mmm nnn ooo.";

        var chunks = RagChunker.chunk(doc(content), 30, 25, Strategy.SENTENCE);

        assertMaxSize(chunks, 30);
        assertEquals("Ddd eee fff. Ggg hhh iii.", chunks.get(1).content(),
                "exactly one trailing sentence is carried — two would breach maxChars");
        for (var i = 1; i < chunks.size(); i++) {
            var previousStart = Integer.parseInt(chunks.get(i - 1).metadata().get("chunk_start"));
            var currentStart = Integer.parseInt(chunks.get(i).metadata().get("chunk_start"));
            assertTrue(currentStart > previousStart,
                    "chunk starts must advance (" + previousStart + " -> " + currentStart + ")");
        }
        assertTrue(chunks.getLast().content().contains("Mmm nnn ooo"),
                "the walk reaches the end of the document");
    }

    // ── Degenerate inputs, shared across strategies ──

    @Test
    void documentThatAlreadyFitsIsReturnedUnchangedByEveryStrategy() {
        var document = doc("short text");
        for (var strategy : Strategy.values()) {
            var chunks = RagChunker.chunk(document, 100, 10, strategy);
            assertEquals(1, chunks.size(), strategy.name());
            assertSame(document, chunks.get(0), strategy.name());
        }
    }

    @Test
    void blankAndWhitespaceOnlyDocumentsAreNeverLost() {
        for (var strategy : Strategy.values()) {
            var empty = doc("");
            assertSame(empty, RagChunker.chunk(empty, 10, 2, strategy).get(0), strategy.name());

            var whitespace = doc("   \n\n \t  \n   \n\n   \t \n    ");
            var chunks = RagChunker.chunk(whitespace, 10, 2, strategy);
            assertEquals(1, chunks.size(), strategy.name());
            assertSame(whitespace, chunks.get(0),
                    strategy + " must return the original when every span is blank");
        }
    }

    @Test
    void oneHugeUnbreakableLineIsWindowedWithinTheBoundByEveryStrategy() {
        var document = doc("x".repeat(5_000));
        for (var strategy : Strategy.values()) {
            var chunks = RagChunker.chunk(document, 200, 40, strategy);
            assertTrue(chunks.size() > 1, strategy.name());
            assertMaxSize(chunks, 200);
        }
    }

    @Test
    void invalidBoundsAreRejectedForEveryStrategy() {
        var document = doc("some content that is long enough to be chunked");
        for (var strategy : Strategy.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> RagChunker.chunk(document, 0, 0, strategy), strategy.name());
            assertThrows(IllegalArgumentException.class,
                    () -> RagChunker.chunk(document, 10, 10, strategy), strategy.name());
            assertThrows(IllegalArgumentException.class,
                    () -> RagChunker.chunk(document, 10, -1, strategy), strategy.name());
        }
    }

    @Test
    void everyStrategyEmitsTheSameChunkAttributionMetadata() {
        var content = "# One\nFirst body sentence. Second body sentence.\n\n"
                + "# Two\nThird body sentence. Fourth body sentence.\n";
        for (var strategy : Strategy.values()) {
            var chunks = RagChunker.chunk(doc(content), 40, 8, strategy);
            assertTrue(chunks.size() > 1, strategy.name());
            for (var i = 0; i < chunks.size(); i++) {
                var metadata = chunks.get(i).metadata();
                assertEquals("guide.md", metadata.get("source_document"), strategy.name());
                assertEquals(Integer.toString(i + 1), metadata.get("chunk_index"), strategy.name());
                assertEquals(Integer.toString(chunks.size()), metadata.get("chunk_count"),
                        strategy.name());
                assertTrue(Integer.parseInt(metadata.get("chunk_end"))
                                > Integer.parseInt(metadata.get("chunk_start")),
                        strategy.name());
                assertEquals("guide.md#chunk-" + (i + 1), chunks.get(i).source(), strategy.name());
            }
        }
    }

    @Test
    void chunkAllAppliesTheStrategyToEveryDocument() {
        var content = "# H1\n" + "alpha ".repeat(40) + "\n# H2\n" + "beta ".repeat(40);

        var chunks = RagChunker.chunkAll(List.of(doc(content), doc(content)), 120, 20,
                Strategy.MARKDOWN);

        assertTrue(chunks.size() >= 4);
        assertMaxSize(chunks, 120);
    }

    private static void assertMaxSize(List<ContextProvider.Document> chunks, int maxChars) {
        for (var chunk : chunks) {
            assertTrue(chunk.content().length() <= maxChars,
                    "chunk exceeded maxChars=" + maxChars + " (" + chunk.content().length()
                            + "): " + chunk.content());
        }
    }
}
