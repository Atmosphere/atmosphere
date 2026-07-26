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
package org.atmosphere.ai;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Generative coverage for structured-output extraction — the step that pulls a
 * JSON object out of whatever prose, markdown fencing, or apology text an
 * instruction-following model wrapped it in.
 *
 * <p>This is a boundary (Correctness Invariant #4): the input is model output,
 * i.e. adversary-influenced text. The properties assert the two behaviours the
 * boundary owes its callers — a well-formed object survives arbitrary wrapping,
 * and anything else is rejected as a typed {@link StructuredOutputException}
 * rather than a raw parser crash or, worse, a silently wrong value.</p>
 */
class StructuredOutputExtractionPropertyTest {

    /** Target type for the parse properties. */
    record Answer(String text, int score, boolean confident) {
    }

    private final JacksonStructuredOutputParser parser = new JacksonStructuredOutputParser();

    // ── Generators ────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Answer> answers() {
        return Combinators.combine(
                        Arbitraries.strings().alpha().numeric().withChars(' ', '-').ofMaxLength(30),
                        Arbitraries.integers().between(-1000, 1000),
                        Arbitraries.of(Boolean.TRUE, Boolean.FALSE))
                .as(Answer::new);
    }

    /** Prose a model prepends or appends around the JSON it was asked for. */
    @Provide
    Arbitrary<String> chatter() {
        return Arbitraries.of(
                "", "Sure! ", "Here is the JSON you asked for:\n",
                "I think this is right.\n\n", "Of course.\n",
                "\n\nLet me know if you need anything else.",
                "Note: values are approximate.\n");
    }

    /** Markdown fences a model wraps the object in. */
    @Provide
    Arbitrary<String> fence() {
        return Arbitraries.of("", "```json", "```");
    }

    /**
     * Text carrying no parseable object. Deliberately excludes the bare JSON
     * literal {@code null}, which Jackson binds to a null record rather than
     * failing — see {@link #bareNullLiteralBindsToNullInsteadOfThrowing()}.
     */
    @Provide
    Arbitrary<String> nonJson() {
        return Arbitraries.of(
                "", "   ", "I cannot answer that.", "[1,2,3]",
                "```json\n```", "{", "}", "}{", "{\"unterminated\": ",
                "The answer is 42.", "<html><body>error</body></html>");
    }

    // ── Properties ────────────────────────────────────────────────────────

    /**
     * A well-formed object survives arbitrary surrounding prose and fencing:
     * whatever the model wraps it in, the parsed record equals the original.
     */
    @Property(tries = 500)
    void wellFormedJsonSurvivesArbitraryWrapping(@ForAll("answers") Answer expected,
                                                 @ForAll("chatter") String before,
                                                 @ForAll("chatter") String after,
                                                 @ForAll("fence") String fence) {
        var json = "{\"text\":\"" + expected.text() + "\",\"score\":" + expected.score()
                + ",\"confident\":" + expected.confident() + "}";
        var wrapped = fence.isEmpty()
                ? before + json + after
                : before + fence + "\n" + json + "\n```" + after;

        var parsed = parser.parse(wrapped, Answer.class);
        assertEquals(expected, parsed);
    }

    /**
     * {@code extractJson} is the boundary's first step and must never throw,
     * whatever text it is handed — including text with no JSON in it at all.
     * A crash here would surface as a 500 on a user-supplied prompt.
     */
    @Property(tries = 500)
    void extractionNeverThrowsOnArbitraryText(@ForAll("chatter") String before,
                                              @ForAll("nonJson") String garbage,
                                              @ForAll("chatter") String after) {
        var input = before + garbage + after;
        assertDoesNotThrow(() -> {
            var extracted = JacksonStructuredOutputParser.extractJson(input);
            assertNotNull(extracted, "extraction must return text, never null");
        });
    }

    /**
     * Output with no parseable object must be rejected as the typed
     * {@link StructuredOutputException} — the failure the pipeline's
     * structured-retry path is written against. Any other exception type would
     * bypass that handling and escape as an unclassified error.
     */
    @Property(tries = 300)
    void unparseableOutputRaisesTheTypedException(@ForAll("nonJson") String garbage) {
        assertThrows(StructuredOutputParser.StructuredOutputException.class,
                () -> parser.parse(garbage, Answer.class));
    }

    /**
     * Characterization of a real gap this fuzzer found (jqwik seed
     * -2347727604474140406, falsifying sample {@code "null"}).
     *
     * <p>A model that answers with the bare JSON literal {@code null} does not
     * take the {@link StructuredOutputParser.StructuredOutputException} path:
     * {@code extractJson} finds no braces and passes {@code "null"} through,
     * and Jackson binds it to a null record. {@code parse} therefore returns
     * {@code null} rather than raising, so the failure surfaces later as an
     * NPE at an unrelated call site instead of as a typed structured-output
     * error the pipeline's structured-retry path is written to handle.</p>
     *
     * <p>This test pins the behaviour as it is today so the gap is visible and
     * so closing it (raising the typed exception instead) is a deliberate,
     * test-updating change rather than a silent one.</p>
     */
    @Example
    void bareNullLiteralBindsToNullInsteadOfThrowing() {
        assertEquals("null", JacksonStructuredOutputParser.extractJson("null"),
                "extraction passes a bare null literal through unchanged");
        assertNull(parser.parse("null", Answer.class),
                "current behaviour: a bare null literal yields a null record, "
                        + "not a StructuredOutputException");
    }

    /**
     * Streaming field extraction sees arbitrary mid-object chunks. It must
     * never throw — it returns an empty Optional until a chunk happens to be a
     * complete {@code "key": value} pair — because it runs on every delta.
     */
    @Property(tries = 500)
    void partialFieldParsingNeverThrows(@ForAll("answers") Answer answer,
                                        @ForAll("cutRatio") int cutPercent) {
        var json = "{\"text\":\"" + answer.text() + "\",\"score\":" + answer.score()
                + ",\"confident\":" + answer.confident() + "}";
        // Feed an arbitrary interior slice, the shape a token delta produces.
        var inner = json.substring(1, json.length() - 1);
        var chunk = inner.substring(0, inner.length() * cutPercent / 100);

        assertDoesNotThrow(() -> {
            var field = parser.parseField(chunk, Answer.class);
            assertNotNull(field, "parseField must return an Optional, never null");
        });
    }

    @Provide
    Arbitrary<Integer> cutRatio() {
        return Arbitraries.integers().between(0, 100);
    }

    /**
     * Extraction is idempotent: re-running it on its own output must not strip
     * anything further. A non-idempotent extractor would corrupt a value that
     * legitimately begins with a brace or a fence marker.
     */
    @Property(tries = 500)
    void extractionIsIdempotent(@ForAll("answers") Answer answer,
                                @ForAll("chatter") String before,
                                @ForAll("fence") String fence) {
        var json = "{\"text\":\"" + answer.text() + "\",\"score\":" + answer.score()
                + ",\"confident\":" + answer.confident() + "}";
        var wrapped = fence.isEmpty() ? before + json
                : before + fence + "\n" + json + "\n```";

        var once = JacksonStructuredOutputParser.extractJson(wrapped);
        var twice = JacksonStructuredOutputParser.extractJson(once);
        assertEquals(once, twice);
    }
}
