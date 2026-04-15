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

class SkillTest {

    @Test
    void constructionWithAllFields() {
        var skill = new Skill("s1", "Search", "searches stuff",
                List.of("search", "web"), Map.of("type", "object"), Map.of("type", "string"));
        assertEquals("s1", skill.id());
        assertEquals("Search", skill.name());
        assertEquals("searches stuff", skill.description());
        assertEquals(List.of("search", "web"), skill.tags());
        assertEquals(Map.of("type", "object"), skill.inputSchema());
        assertEquals(Map.of("type", "string"), skill.outputSchema());
    }

    @Test
    void nullTagsDefaultsToEmptyList() {
        var skill = new Skill("s1", "Search", "desc", null, Map.of(), Map.of());
        assertNotNull(skill.tags());
        assertEquals(0, skill.tags().size());
    }

    @Test
    void nullInputSchemaDefaultsToEmptyMap() {
        var skill = new Skill("s1", "Search", "desc", List.of(), null, Map.of());
        assertNotNull(skill.inputSchema());
        assertEquals(0, skill.inputSchema().size());
    }

    @Test
    void nullOutputSchemaDefaultsToEmptyMap() {
        var skill = new Skill("s1", "Search", "desc", List.of(), Map.of(), null);
        assertNotNull(skill.outputSchema());
        assertEquals(0, skill.outputSchema().size());
    }

    @Test
    void allNullCollectionsDefaultToEmpty() {
        var skill = new Skill("s1", "Search", "desc", null, null, null);
        assertEquals(0, skill.tags().size());
        assertEquals(0, skill.inputSchema().size());
        assertEquals(0, skill.outputSchema().size());
    }

    @Test
    void tagsAreDefensivelyCopied() {
        var mutableTags = new ArrayList<>(List.of("tag1", "tag2"));
        var skill = new Skill("s1", "Search", "desc", mutableTags, Map.of(), Map.of());
        mutableTags.add("tag3");
        assertEquals(2, skill.tags().size());
    }

    @Test
    void tagsAreUnmodifiable() {
        var skill = new Skill("s1", "Search", "desc", List.of("a"), Map.of(), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> skill.tags().add("b"));
    }

    @Test
    void inputSchemaIsDefensivelyCopied() {
        var mutableSchema = new HashMap<String, Object>(Map.of("type", "object"));
        var skill = new Skill("s1", "Search", "desc", List.of(), mutableSchema, Map.of());
        mutableSchema.put("extra", "field");
        assertEquals(1, skill.inputSchema().size());
    }

    @Test
    void inputSchemaIsUnmodifiable() {
        var skill = new Skill("s1", "Search", "desc", List.of(), Map.of("k", "v"), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> skill.inputSchema().put("x", "y"));
    }

    @Test
    void outputSchemaIsDefensivelyCopied() {
        var mutableSchema = new HashMap<String, Object>(Map.of("type", "string"));
        var skill = new Skill("s1", "Search", "desc", List.of(), Map.of(), mutableSchema);
        mutableSchema.put("extra", "field");
        assertEquals(1, skill.outputSchema().size());
    }

    @Test
    void outputSchemaIsUnmodifiable() {
        var skill = new Skill("s1", "Search", "desc", List.of(), Map.of(), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> skill.outputSchema().put("x", "y"));
    }

    @Test
    void nullIdAndNameAreAllowed() {
        var skill = new Skill(null, null, null, null, null, null);
        assertEquals(null, skill.id());
        assertEquals(null, skill.name());
        assertEquals(null, skill.description());
    }

    @Test
    void equalityBasedOnComponents() {
        var a = new Skill("s1", "Search", "desc", List.of("t"), Map.of("k", "v"), Map.of());
        var b = new Skill("s1", "Search", "desc", List.of("t"), Map.of("k", "v"), Map.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
