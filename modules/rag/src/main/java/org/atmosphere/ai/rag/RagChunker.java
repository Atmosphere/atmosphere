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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Small text chunking utility for RAG ingestion.
 *
 * <p>Three strategies are available, selected per call or process-wide via
 * {@link #defaultStrategy()}:</p>
 *
 * <ul>
 *   <li>{@link Strategy#FIXED} — the historical fixed-size character window
 *       with an overlap tail. Content-agnostic and predictable, but splits
 *       mid-sentence and mid-structure. <strong>The default</strong>, so every
 *       existing caller keeps byte-identical output.</li>
 *   <li>{@link Strategy#MARKDOWN} — cuts on markdown ATX headings, keeping
 *       fenced code blocks intact, then packs whole sections up to
 *       {@code maxChars}. A section larger than {@code maxChars} falls back to
 *       the fixed window <em>within that section</em>, so the size bound always
 *       holds. Chunks carry their heading as {@value #HEADING_METADATA_KEY}.</li>
 *   <li>{@link Strategy#SENTENCE} — cuts on sentence boundaries via
 *       {@link BreakIterator} (JDK-only, no new dependency) and packs whole
 *       sentences up to {@code maxChars}, carrying trailing sentences forward
 *       as the overlap. A single sentence larger than {@code maxChars} falls
 *       back to the fixed window within that sentence.</li>
 * </ul>
 *
 * <p>Every strategy honours the same contract: a document that already fits in
 * {@code maxChars} is returned unchanged (the same instance), no emitted chunk
 * exceeds {@code maxChars}, and chunks carry the same {@code source_document} /
 * {@code chunk_index} / {@code chunk_count} / {@code chunk_start} /
 * {@code chunk_end} metadata.</p>
 */
public final class RagChunker {

    private static final Logger logger = LoggerFactory.getLogger(RagChunker.class);

    public static final int DEFAULT_MAX_CHARS = 1_200;
    public static final int DEFAULT_OVERLAP_CHARS = 150;

    /**
     * Config key selecting the process-wide default strategy:
     * {@code fixed} (default), {@code markdown}, or {@code sentence}. Read from
     * the system properties by {@link #defaultStrategy()}; Spring Boot
     * applications bind the same key from {@code application.yml} through
     * {@code org.atmosphere.ai.rag.spring.RagChunkerAutoConfiguration}.
     */
    public static final String STRATEGY_KEY = "atmosphere.ai.rag.chunker";

    /** Metadata key carrying the markdown heading a chunk was cut under. */
    public static final String HEADING_METADATA_KEY = "chunk_heading";

    /** {@code null} means "resolve from {@value #STRATEGY_KEY}". */
    private static volatile Strategy defaultStrategy;

    private RagChunker() {
    }

    /** How a document is cut into retrievable chunks. */
    public enum Strategy {

        /** Fixed-size character windows with an overlap tail (the default). */
        FIXED,

        /** Markdown heading / code-fence aware sections packed to the size bound. */
        MARKDOWN,

        /**
         * Sentence-boundary aware packing with a sentence-granular overlap.
         *
         * <p>Boundaries come from {@link BreakIterator}, whose rules need a
         * capitalized continuation to call a period a sentence end — {@code
         * "one. two."} is one sentence to it, {@code "One. Two."} is two. Prose
         * splits as expected; all-lowercase input degrades to the fixed-window
         * fallback rather than mis-splitting.</p>
         */
        SENTENCE;

        /**
         * Parse a configured value, case-insensitively. An unknown value is a
         * typo in a retrieval knob, not a fatal misconfiguration: it logs at
         * WARN and degrades to {@link #FIXED} (the historical behaviour)
         * rather than failing ingestion.
         *
         * @param value the configured value; {@code null}/blank yields {@link #FIXED}
         * @return the selected strategy, never {@code null}
         */
        public static Strategy from(String value) {
            if (value == null || value.isBlank()) {
                return FIXED;
            }
            var normalized = value.trim().toUpperCase(Locale.ROOT);
            for (var candidate : values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            logger.warn("Unknown {} value '{}' — falling back to fixed-window chunking",
                    STRATEGY_KEY, value);
            return FIXED;
        }
    }

    /**
     * The strategy the no-strategy overloads use: the value installed by
     * {@link #setDefaultStrategy(Strategy)} when present, otherwise the
     * {@value #STRATEGY_KEY} system property, otherwise {@link Strategy#FIXED}.
     *
     * @return the effective default strategy, never {@code null}
     */
    public static Strategy defaultStrategy() {
        var installed = defaultStrategy;
        return installed != null ? installed : Strategy.from(System.getProperty(STRATEGY_KEY));
    }

    /**
     * Install the process-wide default strategy — the seam the Spring bridge
     * uses to publish {@code atmosphere.ai.rag.chunker} from application
     * config, since the chunker is a static utility with no container handle.
     *
     * @param strategy the strategy to install; {@code null} reverts to the
     *                 {@value #STRATEGY_KEY} system property / {@link Strategy#FIXED}
     */
    public static void setDefaultStrategy(Strategy strategy) {
        defaultStrategy = strategy;
        logger.debug("RAG chunking strategy set to {}", strategy != null ? strategy : "(unset)");
    }

    /**
     * Split a document into retrievable chunks using the default production-safe size.
     *
     * @param document document to chunk
     * @return the original document when it already fits, otherwise source-attributed chunks
     */
    public static List<ContextProvider.Document> chunk(ContextProvider.Document document) {
        return chunk(document, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    /**
     * Split many documents into retrievable chunks using the default size.
     *
     * @param documents documents to chunk
     * @return chunked documents
     */
    public static List<ContextProvider.Document> chunkAll(Collection<ContextProvider.Document> documents) {
        return chunkAll(documents, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    /**
     * Split many documents into retrievable chunks with the
     * {@linkplain #defaultStrategy() configured} strategy.
     *
     * @param documents documents to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @return chunked documents
     */
    public static List<ContextProvider.Document> chunkAll(
            Collection<ContextProvider.Document> documents, int maxChars, int overlapChars) {
        return chunkAll(documents, maxChars, overlapChars, defaultStrategy());
    }

    /**
     * Split many documents into retrievable chunks with an explicit strategy.
     *
     * @param documents documents to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @param strategy the chunking strategy
     * @return chunked documents
     */
    public static List<ContextProvider.Document> chunkAll(
            Collection<ContextProvider.Document> documents, int maxChars, int overlapChars,
            Strategy strategy) {
        Objects.requireNonNull(documents, "documents");
        var chunks = new ArrayList<ContextProvider.Document>();
        for (var document : documents) {
            chunks.addAll(chunk(document, maxChars, overlapChars, strategy));
        }
        return List.copyOf(chunks);
    }

    /**
     * Split a document into retrievable chunks with the
     * {@linkplain #defaultStrategy() configured} strategy.
     *
     * @param document document to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @return the original document when it already fits, otherwise source-attributed chunks
     */
    public static List<ContextProvider.Document> chunk(
            ContextProvider.Document document, int maxChars, int overlapChars) {
        return chunk(document, maxChars, overlapChars, defaultStrategy());
    }

    /**
     * Split a document into retrievable chunks with an explicit strategy.
     *
     * @param document document to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @param strategy the chunking strategy
     * @return the original document when it already fits, otherwise source-attributed chunks
     */
    public static List<ContextProvider.Document> chunk(
            ContextProvider.Document document, int maxChars, int overlapChars, Strategy strategy) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(strategy, "strategy");
        validate(maxChars, overlapChars);

        var content = Objects.requireNonNull(document.content(), "document.content");
        var source = normalizeSource(document.source());
        if (content.isBlank() || content.length() <= maxChars) {
            return List.of(document);
        }

        var spans = switch (strategy) {
            case FIXED -> fixedSpans(content, maxChars, overlapChars);
            case MARKDOWN -> markdownSpans(content, maxChars, overlapChars);
            case SENTENCE -> sentenceSpans(content, maxChars, overlapChars);
        };
        if (spans.isEmpty()) {
            // Every candidate span was whitespace-only. Returning the original
            // keeps the "chunking never drops a document" contract intact.
            return List.of(document);
        }

        var result = new ArrayList<ContextProvider.Document>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            var metadata = new HashMap<>(document.metadata());
            metadata.put("source_document", source);
            metadata.put("chunk_index", Integer.toString(i + 1));
            metadata.put("chunk_count", Integer.toString(spans.size()));
            metadata.put("chunk_start", Integer.toString(span.start()));
            metadata.put("chunk_end", Integer.toString(span.end()));
            if (span.heading() != null && !span.heading().isBlank()) {
                metadata.put(HEADING_METADATA_KEY, span.heading());
            }
            result.add(new ContextProvider.Document(
                    span.text(),
                    source + "#chunk-" + (i + 1),
                    document.score(),
                    Map.copyOf(metadata)));
        }
        return List.copyOf(result);
    }

    /** One emitted chunk: bounds in the source content, trimmed text, optional heading. */
    private record Span(int start, int end, String text, String heading) { }

    /** One markdown section: bounds in the source content plus the heading it sits under. */
    private record Section(int start, int end, String heading) { }

    // ── Fixed-window strategy (the historical behaviour) ──

    private static List<Span> fixedSpans(String content, int maxChars, int overlapChars) {
        var spans = new ArrayList<Span>();
        int start = 0;
        while (start < content.length()) {
            var end = Math.min(content.length(), start + maxChars);
            if (end < content.length()) {
                end = chooseBreak(content, start, end);
            }
            addSpan(spans, content, start, end, null);
            if (end >= content.length()) {
                break;
            }
            var nextStart = Math.max(0, end - overlapChars);
            while (nextStart < end && Character.isWhitespace(content.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart > start ? nextStart : end;
        }
        return spans;
    }

    private static int chooseBreak(String content, int start, int limit) {
        var min = start + Math.max(1, (limit - start) / 2);
        var paragraph = content.lastIndexOf("\n\n", limit);
        if (paragraph >= min) {
            return paragraph;
        }
        for (int i = limit; i >= min; i--) {
            if (Character.isWhitespace(content.charAt(i - 1))) {
                return i;
            }
        }
        return limit;
    }

    // ── Markdown strategy ──

    /**
     * Pack whole heading-delimited sections up to {@code maxChars}. Overlap is
     * deliberately NOT applied between sections: the structural boundary is the
     * point of this strategy, and duplicating one section's prose into its
     * neighbour would blur it. Overlap still applies inside the fixed-window
     * fallback used for an oversized section.
     */
    private static List<Span> markdownSpans(String content, int maxChars, int overlapChars) {
        var spans = new ArrayList<Span>();
        var chunkStart = -1;
        var chunkEnd = -1;
        String chunkHeading = null;
        for (var section : markdownSections(content)) {
            var sectionLength = section.end() - section.start();
            if (sectionLength > maxChars) {
                if (chunkStart >= 0) {
                    addSpan(spans, content, chunkStart, chunkEnd, chunkHeading);
                    chunkStart = -1;
                    chunkHeading = null;
                }
                var body = content.substring(section.start(), section.end());
                for (var inner : fixedSpans(body, maxChars, overlapChars)) {
                    addSpan(spans, content, section.start() + inner.start(),
                            section.start() + inner.end(), section.heading());
                }
                continue;
            }
            if (chunkStart >= 0 && (chunkEnd - chunkStart) + sectionLength > maxChars) {
                addSpan(spans, content, chunkStart, chunkEnd, chunkHeading);
                chunkStart = -1;
                chunkHeading = null;
            }
            if (chunkStart < 0) {
                chunkStart = section.start();
                chunkHeading = section.heading();
            }
            chunkEnd = section.end();
        }
        if (chunkStart >= 0) {
            addSpan(spans, content, chunkStart, chunkEnd, chunkHeading);
        }
        return spans;
    }

    /**
     * Cut the content at ATX headings ({@code # }..{@code ###### }) that are
     * not inside a fenced code block, so a {@code # comment} line in a shell
     * snippet never splits the snippet away from the prose explaining it.
     */
    private static List<Section> markdownSections(String content) {
        var sections = new ArrayList<Section>();
        var length = content.length();
        var sectionStart = 0;
        String heading = null;
        var inFence = false;
        var lineStart = 0;
        while (lineStart <= length) {
            var newline = content.indexOf('\n', lineStart);
            var lineEnd = newline < 0 ? length : newline;
            var line = content.substring(lineStart, lineEnd).strip();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence;
            } else if (!inFence && isAtxHeading(line)) {
                if (lineStart > sectionStart) {
                    sections.add(new Section(sectionStart, lineStart, heading));
                    sectionStart = lineStart;
                }
                heading = headingText(line);
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        if (sectionStart < length) {
            sections.add(new Section(sectionStart, length, heading));
        }
        return sections;
    }

    private static boolean isAtxHeading(String line) {
        var hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes == 0 || hashes > 6) {
            return false;
        }
        return hashes < line.length() && Character.isWhitespace(line.charAt(hashes));
    }

    private static String headingText(String line) {
        var from = 0;
        while (from < line.length() && line.charAt(from) == '#') {
            from++;
        }
        var to = line.length();
        while (to > from
                && (line.charAt(to - 1) == '#' || Character.isWhitespace(line.charAt(to - 1)))) {
            to--;
        }
        return line.substring(from, to).strip();
    }

    // ── Sentence strategy ──

    /**
     * Pack whole sentences up to {@code maxChars}, then carry the trailing
     * sentences that fit in {@code overlapChars} into the next chunk so a
     * retrieval hit never loses the sentence that introduced its subject.
     */
    private static List<Span> sentenceSpans(String content, int maxChars, int overlapChars) {
        var bounds = sentenceBounds(content);
        var spans = new ArrayList<Span>();
        var chunkStartIdx = -1;
        for (var i = 0; i < bounds.size(); i++) {
            var sentence = bounds.get(i);
            if (sentence[1] - sentence[0] > maxChars) {
                // One sentence bigger than the bound (minified text, a table
                // row, a URL wall). Flush what we have, then window it.
                if (chunkStartIdx >= 0) {
                    addSpan(spans, content, bounds.get(chunkStartIdx)[0], bounds.get(i - 1)[1], null);
                    chunkStartIdx = -1;
                }
                var body = content.substring(sentence[0], sentence[1]);
                for (var inner : fixedSpans(body, maxChars, overlapChars)) {
                    addSpan(spans, content, sentence[0] + inner.start(),
                            sentence[0] + inner.end(), null);
                }
                continue;
            }
            if (chunkStartIdx >= 0 && sentence[1] - bounds.get(chunkStartIdx)[0] > maxChars) {
                var flushedEnd = bounds.get(i - 1)[1];
                addSpan(spans, content, bounds.get(chunkStartIdx)[0], flushedEnd, null);
                chunkStartIdx = overlapStartIndex(bounds, chunkStartIdx, i, flushedEnd,
                        maxChars, overlapChars);
            }
            if (chunkStartIdx < 0) {
                chunkStartIdx = i;
            }
        }
        if (chunkStartIdx >= 0 && !bounds.isEmpty()) {
            addSpan(spans, content, bounds.get(chunkStartIdx)[0], bounds.getLast()[1], null);
        }
        return spans;
    }

    /**
     * The sentence index the next chunk starts at: the earliest already-emitted
     * sentence whose tail fits in {@code overlapChars} and still leaves the new
     * chunk inside {@code maxChars}. Never earlier than {@code chunkStartIdx + 1},
     * so the walk always makes progress and no chunk repeats wholesale.
     */
    private static int overlapStartIndex(List<int[]> bounds, int chunkStartIdx, int current,
                                         int flushedEnd, int maxChars, int overlapChars) {
        var start = current;
        for (var j = current - 1; j > chunkStartIdx; j--) {
            if (flushedEnd - bounds.get(j)[0] > overlapChars) {
                break;
            }
            if (bounds.get(current)[1] - bounds.get(j)[0] > maxChars) {
                break;
            }
            start = j;
        }
        return start;
    }

    private static List<int[]> sentenceBounds(String content) {
        var iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(content);
        var bounds = new ArrayList<int[]>();
        var start = iterator.first();
        for (var end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            bounds.add(new int[] {start, end});
        }
        return bounds;
    }

    // ── Shared helpers ──

    private static void addSpan(List<Span> spans, String content, int start, int end, String heading) {
        var text = content.substring(start, end).trim();
        if (!text.isEmpty()) {
            spans.add(new Span(start, end, text, heading));
        }
    }

    private static void validate(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be non-negative and smaller than maxChars");
        }
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "document" : source;
    }
}
