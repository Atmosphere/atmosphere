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

    @Test
    public void allExpandsToEveryConcreteFeature() {
        var expanded = Harness.expand(new Harness[]{Harness.ALL});

        assertEquals(Set.of(Harness.MEMORY, Harness.CACHE, Harness.DELEGATION), expanded);
        assertFalse(expanded.contains(Harness.ALL),
                "the ALL sentinel must never appear in an expanded set");
    }

    @Test
    public void duplicatesCollapse() {
        var expanded = Harness.expand(
                new Harness[]{Harness.MEMORY, Harness.MEMORY, Harness.ALL, Harness.CACHE});

        assertEquals(Set.of(Harness.MEMORY, Harness.CACHE, Harness.DELEGATION), expanded);
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
