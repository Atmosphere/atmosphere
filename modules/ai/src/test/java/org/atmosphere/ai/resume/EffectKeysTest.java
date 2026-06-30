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
package org.atmosphere.ai.resume;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins idempotency-key derivation: canonical (sorted-key) argument rendering,
 * order-independence, occurrence disambiguation, positional LLM-round keys, and
 * unambiguous part boundaries in the digest.
 */
class EffectKeysTest {

    @Test
    void canonicalJsonSortsKeysRecursively() {
        // Insertion order deliberately reversed; canonicalization must sort.
        var nested = new LinkedHashMap<String, Object>();
        nested.put("d", 2);
        nested.put("c", 3);
        var args = new LinkedHashMap<String, Object>();
        args.put("b", 1);
        args.put("a", nested);

        assertEquals("{\"a\":{\"c\":3,\"d\":2},\"b\":1}", EffectKeys.canonicalJson(args));
    }

    @Test
    void identicalArgsRegardlessOfOrderProduceIdenticalToolKey() {
        var args1 = new LinkedHashMap<String, Object>();
        args1.put("x", 1);
        args1.put("y", 2);
        var args2 = new LinkedHashMap<String, Object>();
        args2.put("y", 2);
        args2.put("x", 1);

        assertEquals(EffectKeys.toolCall("run", "delete_row", args1, 0),
                EffectKeys.toolCall("run", "delete_row", args2, 0),
                "argument map order must not change the key");
    }

    @Test
    void occurrenceDisambiguatesRepeatedIdenticalCalls() {
        var args = Map.<String, Object>of("id", 7);
        assertNotEquals(EffectKeys.toolCall("run", "delete_row", args, 0),
                EffectKeys.toolCall("run", "delete_row", args, 1),
                "the same call repeated must get distinct keys via the occurrence ordinal");
    }

    @Test
    void differentToolNameOrRunIdChangesKey() {
        var args = Map.<String, Object>of("id", 7);
        assertNotEquals(EffectKeys.toolCall("run", "delete_row", args, 0),
                EffectKeys.toolCall("run", "insert_row", args, 0));
        assertNotEquals(EffectKeys.toolCall("runA", "delete_row", args, 0),
                EffectKeys.toolCall("runB", "delete_row", args, 0));
    }

    @Test
    void llmRoundKeyIsPositionalAndStable() {
        assertEquals(EffectKeys.llmRound("run", 2), EffectKeys.llmRound("run", 2),
                "same run + round index is stable across drives");
        assertNotEquals(EffectKeys.llmRound("run", 0), EffectKeys.llmRound("run", 1));
    }

    @Test
    void runInputKeyIsStableAndDistinctFromRounds() {
        assertEquals(EffectKeys.runInput("run"), EffectKeys.runInput("run"));
        assertNotEquals(EffectKeys.runInput("run"), EffectKeys.llmRound("run", 0));
    }

    @Test
    void digestPartBoundariesAreUnambiguous() {
        assertNotEquals(EffectKeys.sha256Hex("a", "bc"), EffectKeys.sha256Hex("ab", "c"),
                "the null separator must keep concatenated parts distinct");
    }

    @Test
    void nullArgsCanonicalizeToNullLiteral() {
        assertEquals("null", EffectKeys.canonicalJson(null));
    }
}
