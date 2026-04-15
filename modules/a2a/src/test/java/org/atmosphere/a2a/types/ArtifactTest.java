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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactTest {

    @Test
    void constructionWithAllFields() {
        var parts = List.<Part>of(new Part.TextPart("hello"));
        var artifact = new Artifact("a1", "myArtifact", "desc", parts, Map.of("k", "v"));
        assertEquals("a1", artifact.artifactId());
        assertEquals("myArtifact", artifact.name());
        assertEquals("desc", artifact.description());
        assertEquals(1, artifact.parts().size());
        assertEquals(Map.of("k", "v"), artifact.metadata());
    }

    @Test
    void nullPartsDefaultsToEmptyList() {
        var artifact = new Artifact("a1", "n", "d", null, Map.of());
        assertNotNull(artifact.parts());
        assertEquals(0, artifact.parts().size());
    }

    @Test
    void nullMetadataDefaultsToEmptyMap() {
        var artifact = new Artifact("a1", "n", "d", List.of(), null);
        assertNotNull(artifact.metadata());
        assertEquals(0, artifact.metadata().size());
    }

    @Test
    void partsAreDefensivelyCopied() {
        var mutableParts = new ArrayList<Part>(List.of(new Part.TextPart("a")));
        var artifact = new Artifact("a1", "n", "d", mutableParts, Map.of());
        mutableParts.add(new Part.TextPart("b"));
        assertEquals(1, artifact.parts().size());
    }

    @Test
    void partsAreUnmodifiable() {
        var artifact = new Artifact("a1", "n", "d", List.of(new Part.TextPart("a")), Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> artifact.parts().add(new Part.TextPart("b")));
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var mutableMeta = new HashMap<>(Map.of("k1", (Object) "v1"));
        var artifact = new Artifact("a1", "n", "d", List.of(), mutableMeta);
        mutableMeta.put("k2", "v2");
        assertEquals(1, artifact.metadata().size());
    }

    @Test
    void metadataIsUnmodifiable() {
        var artifact = new Artifact("a1", "n", "d", List.of(), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class,
                () -> artifact.metadata().put("x", "y"));
    }

    @Test
    void textFactoryCreatesTextArtifact() {
        var artifact = Artifact.text("hello world");
        assertNotNull(artifact.artifactId());
        assertEquals(1, artifact.parts().size());
        var part = (Part.TextPart) artifact.parts().getFirst();
        assertEquals("hello world", part.text());
        assertEquals(null, artifact.name());
        assertEquals(null, artifact.description());
        assertEquals(0, artifact.metadata().size());
    }

    @Test
    void namedFactoryCreatesNamedArtifact() {
        var parts = List.<Part>of(new Part.TextPart("content"), new Part.TextPart("more"));
        var artifact = Artifact.named("report", "A report", parts);
        assertNotNull(artifact.artifactId());
        assertEquals("report", artifact.name());
        assertEquals("A report", artifact.description());
        assertEquals(2, artifact.parts().size());
        assertEquals(0, artifact.metadata().size());
    }

    @Test
    void textFactoryGeneratesUniqueIds() {
        var a1 = Artifact.text("a");
        var a2 = Artifact.text("b");
        assertNotNull(a1.artifactId());
        assertNotNull(a2.artifactId());
        // UUIDs should be different
        assertEquals(false, a1.artifactId().equals(a2.artifactId()));
    }

    @Test
    void equalityBasedOnComponents() {
        var parts = List.<Part>of(new Part.TextPart("hello"));
        var a = new Artifact("id1", "name", "desc", parts, Map.of());
        var b = new Artifact("id1", "name", "desc", parts, Map.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
