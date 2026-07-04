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
package org.atmosphere.ai.preset;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HarnessTest {

    /** Every concrete feature {@link Harness#ALL} must expand to — now five. */
    private static final Set<Harness> ALL_CONCRETE = Set.of(
            Harness.MEMORY, Harness.CACHE, Harness.DELEGATION,
            Harness.PLANNING, Harness.FILESYSTEM);

    @Test
    public void allExpandsToEveryConcreteFeature() {
        var expanded = Harness.expand(new Harness[]{Harness.ALL});

        assertEquals(ALL_CONCRETE, expanded);
        assertFalse(expanded.contains(Harness.ALL),
                "the ALL sentinel must never appear in an expanded set");
    }

    @Test
    public void allExpansionCoversEveryEnumConstantExceptTheSentinel() {
        // Guards the expansion against drift: adding a Harness constant
        // without teaching expand() about it must fail here, not in prod.
        var expanded = Harness.expand(new Harness[]{Harness.ALL});
        var everyConcrete = java.util.EnumSet.allOf(Harness.class);
        everyConcrete.remove(Harness.ALL);

        assertEquals(everyConcrete, expanded,
                "ALL must expand to every concrete Harness constant");
    }

    @Test
    public void duplicatesCollapse() {
        var expanded = Harness.expand(
                new Harness[]{Harness.MEMORY, Harness.MEMORY, Harness.ALL, Harness.CACHE});

        assertEquals(ALL_CONCRETE, expanded);
    }

    @Test
    public void singleFeaturePassesThrough() {
        assertEquals(Set.of(Harness.DELEGATION),
                Harness.expand(new Harness[]{Harness.DELEGATION}));
    }

    @Test
    public void emptyStaysEmpty() {
        assertTrue(Harness.expand(new Harness[0]).isEmpty());
    }

    @Test
    public void nullReadsAsEmpty() {
        assertTrue(Harness.expand(null).isEmpty());
    }
}
