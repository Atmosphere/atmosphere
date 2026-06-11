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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    private static ContextProvider.Document doc(String source) {
        return new ContextProvider.Document("content of " + source, source, 1.0);
    }

    @Test
    void ranksAgreedDocumentsFirstAndDeduplicates() {
        var listA = List.of(doc("a"), doc("b"), doc("c"));
        var listB = List.of(doc("a"), doc("d"));

        var fused = RrfFusion.fuse(List.of(listA, listB), RrfFusion.DEFAULT_K, 10);

        assertEquals("a", fused.get(0).source(),
                "the document both retrievers rank first must win");
        assertEquals(4, fused.size(), "duplicate 'a' collapses to one; a,b,c,d remain");
        assertEquals(List.of("a", "b", "c", "d").size(),
                fused.stream().map(ContextProvider.Document::source).distinct().count());
    }

    @Test
    void topNCapsTheFusedResult() {
        var listA = List.of(doc("a"), doc("b"), doc("c"));
        var listB = List.of(doc("a"), doc("b"));
        var fused = RrfFusion.fuse(List.of(listA, listB), RrfFusion.DEFAULT_K, 1);
        assertEquals(1, fused.size());
        assertEquals("a", fused.get(0).source());
    }

    @Test
    void agreementBeatsASingleTopHit() {
        // 'x' is rank-1 in one list only; 'y' is rank-2 in BOTH. Agreement wins.
        var listA = List.of(doc("x"), doc("y"));
        var listB = List.of(doc("z"), doc("y"));
        var fused = RrfFusion.fuse(List.of(listA, listB), RrfFusion.DEFAULT_K, 10);
        assertEquals("y", fused.get(0).source(),
                "a doc both retrievers surface (even mid-rank) beats a single retriever's top hit");
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertTrue(RrfFusion.fuse(List.of(), RrfFusion.DEFAULT_K, 5).isEmpty());
        assertTrue(RrfFusion.fuse(null, RrfFusion.DEFAULT_K, 5).isEmpty());
        var fused = RrfFusion.fuse(java.util.Arrays.asList(null, List.of(doc("a"))),
                RrfFusion.DEFAULT_K, 5);
        assertEquals(1, fused.size());
        assertEquals("a", fused.get(0).source());
    }
}
