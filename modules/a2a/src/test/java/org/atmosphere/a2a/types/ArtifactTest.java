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
package org.atmosphere.a2a.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactTest {

    @Test
    void textFactoryProducesTextPart() {
        var a = Artifact.text("hello");
        assertEquals(1, a.parts().size());
        assertEquals("hello", a.parts().getFirst().text());
        assertNotNull(a.artifactId());
    }

    @Test
    void namedFactoryUsesProvidedName() {
        var a = Artifact.named("report", "Quarterly", List.of(Part.text("body")));
        assertEquals("report", a.name());
        assertEquals("Quarterly", a.description());
        assertEquals(1, a.parts().size());
    }

    @Test
    void partsListIsImmutable() {
        var a = Artifact.text("x");
        assertThrows(UnsupportedOperationException.class,
                () -> a.parts().add(Part.text("y")));
    }
}
