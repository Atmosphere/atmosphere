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
package org.atmosphere.ai.llm;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generative coverage for {@link ToolCallAccumulator}, which reassembles a tool
 * call's {@code arguments} JSON from however many {@code delta.tool_calls}
 * fragments the provider chose to split it into.
 *
 * <p>The fragmentation is entirely provider-controlled: a boundary can land
 * inside a key, inside a string value, between a backslash and its escapee, or
 * inside a multi-byte character. {@code ToolCallAccumulatorTest} pins a handful
 * of hand-chosen splits; these properties fuzz <em>all</em> of them and assert
 * the invariant that matters — reassembly is split-invariant, so the parsed
 * arguments are always equal to what an unfragmented delivery would produce.</p>
 */
class ToolCallReassemblyPropertyTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // ── Generators ────────────────────────────────────────────────────────

    /**
     * A tool-argument object with values chosen to include the characters that
     * make naive fragment handling fail: quotes, backslashes, braces, newlines
     * and non-BMP code points.
     */
    @Provide
    Arbitrary<Map<String, Object>> toolArguments() {
        var key = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        Arbitrary<Object> value = Arbitraries.oneOf(
                Arbitraries.strings()
                        .withChars("abc \"\\{}[]:,\n\t".toCharArray())
                        .withChars('é', 'ß', '中')
                        .ofMaxLength(24)
                        .map(s -> (Object) s),
                Arbitraries.integers().between(-10_000, 10_000).map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
        return Arbitraries.maps(key, value).ofMinSize(0).ofMaxSize(6)
                .map(LinkedHashMap::new);
    }

    /** Split points, as fractions, that carve the payload into fragments. */
    @Provide
    Arbitrary<List<Integer>> splitPercents() {
        return Arbitraries.integers().between(0, 100).list().ofMaxSize(12);
    }

    // ── Properties ────────────────────────────────────────────────────────

    /**
     * The core invariant: for any valid arguments object and any fragmentation
     * of its serialized form, the accumulator parses to a map equal to the one
     * a single-fragment delivery yields. A split-sensitive accumulator would
     * silently hand a tool the wrong arguments.
     */
    @Property(tries = 500)
    void fragmentationDoesNotChangeTheParsedArguments(
            @ForAll("toolArguments") Map<String, Object> arguments,
            @ForAll("splitPercents") List<Integer> splits) {
        var json = MAPPER.writeValueAsString(arguments);

        var whole = new ToolCallAccumulator();
        whole.appendArguments(json);
        var expected = whole.argumentsAsMap(MAPPER);

        var fragmented = new ToolCallAccumulator();
        for (var fragment : fragment(json, splits)) {
            fragmented.appendArguments(fragment);
        }
        var actual = fragmented.argumentsAsMap(MAPPER);

        assertEquals(json, fragmented.arguments(),
                "the reassembled buffer must be byte-identical to the original JSON");
        assertEquals(expected, actual);
        assertFalse(actual.containsKey("__raw"),
                "valid JSON must parse, never fall back to the __raw passthrough");
    }

    /**
     * Every prefix of a valid arguments payload is, by construction, either
     * valid JSON or not — but in neither case may {@code argumentsAsMap} throw.
     * A mid-stream read (the shape a cancel or a provider reset produces) must
     * degrade to the documented {@code __raw} passthrough, never an exception.
     */
    @Property(tries = 500)
    void anyPrefixParsesOrFallsBackButNeverThrows(
            @ForAll("toolArguments") Map<String, Object> arguments,
            @ForAll("splitPercents") List<Integer> splits) {
        var json = MAPPER.writeValueAsString(arguments);
        var cut = splits.isEmpty() ? json.length() / 2
                : json.length() * (splits.get(0) % 101) / 100;

        var accumulator = new ToolCallAccumulator();
        accumulator.appendArguments(json.substring(0, cut));

        // Must not throw, and must return a usable map either way.
        var parsed = accumulator.argumentsAsMap(MAPPER);
        if (parsed.containsKey("__raw")) {
            assertEquals(json.substring(0, cut), parsed.get("__raw"),
                    "the raw passthrough must carry the accumulated buffer verbatim");
        }
    }

    /**
     * Interleaving fragments from two concurrent tool calls must not mix: each
     * accumulator owns its own buffer, which is what lets a client key them by
     * content-block index. A shared or static buffer would show up here.
     */
    @Property(tries = 300)
    void concurrentAccumulatorsDoNotShareState(
            @ForAll("toolArguments") Map<String, Object> first,
            @ForAll("toolArguments") Map<String, Object> second,
            @ForAll("splitPercents") List<Integer> splits) {
        var firstJson = MAPPER.writeValueAsString(first);
        var secondJson = MAPPER.writeValueAsString(second);

        var a = new ToolCallAccumulator();
        var b = new ToolCallAccumulator();
        a.setId("call_a");
        b.setId("call_b");
        a.setFunctionName("alpha");
        b.setFunctionName("beta");

        var fragmentsA = fragment(firstJson, splits);
        var fragmentsB = fragment(secondJson, splits);
        for (int i = 0; i < Math.max(fragmentsA.size(), fragmentsB.size()); i++) {
            if (i < fragmentsA.size()) {
                a.appendArguments(fragmentsA.get(i));
            }
            if (i < fragmentsB.size()) {
                b.appendArguments(fragmentsB.get(i));
            }
        }

        assertEquals(firstJson, a.arguments());
        assertEquals(secondJson, b.arguments());
        assertEquals("call_a", a.id());
        assertEquals("call_b", b.id());
        assertEquals(MAPPER.readValue(firstJson, Map.class), a.argumentsAsMap(MAPPER));
        assertEquals(MAPPER.readValue(secondJson, Map.class), b.argumentsAsMap(MAPPER));
    }

    /**
     * A tool taking no arguments produces no fragments at all, or only blank
     * ones. Both must read as "no arguments" — an empty map, not a
     * {@code __raw} entry a tool would then have to special-case.
     */
    @Property(tries = 200)
    void blankOnlyFragmentsYieldAnEmptyMap(
            @ForAll("blankFragments") List<String> blanks) {
        var accumulator = new ToolCallAccumulator();
        for (var blank : blanks) {
            accumulator.appendArguments(blank);
        }
        assertTrue(accumulator.argumentsAsMap(MAPPER).isEmpty());
    }

    @Provide
    Arbitrary<List<String>> blankFragments() {
        return Arbitraries.of("", " ", "\t", "\n", "  \r\n ").list().ofMaxSize(8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Carve {@code text} at the given percentage offsets, in ascending order. */
    private static List<String> fragment(String text, List<Integer> splitPercents) {
        var cuts = splitPercents.stream()
                .map(p -> text.length() * Math.floorMod(p, 101) / 100)
                .distinct().sorted().toList();
        var fragments = new ArrayList<String>();
        var previous = 0;
        for (var cut : cuts) {
            if (cut > previous) {
                fragments.add(text.substring(previous, cut));
                previous = cut;
            }
        }
        fragments.add(text.substring(previous));
        return fragments;
    }
}
