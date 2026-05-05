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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PromptLoaderTest {

    @AfterEach
    public void tearDown() {
        PromptLoader.clearCache();
    }

    @Test
    public void testLoadsMarkdownResource() {
        var prompt = PromptLoader.load("prompts/test-system-prompt.md");
        assertEquals("You are a helpful assistant.", prompt);
    }

    @Test
    public void testCachesResult() {
        var first = PromptLoader.load("prompts/test-system-prompt.md");
        var second = PromptLoader.load("prompts/test-system-prompt.md");
        assertSame(first, second);
    }

    @Test
    public void testThrowsOnMissingResource() {
            assertThrows(IllegalArgumentException.class, () -> {
            PromptLoader.load("prompts/does-not-exist.md");
            });
    }

    @Test
    public void testTrimsWhitespace() {
        var prompt = PromptLoader.load("prompts/test-system-prompt.md");
        assertFalse(prompt.endsWith("\n"));
    }

    @Test
    public void stripFrontmatter_returnsBodyWithoutYaml() {
        var input = "---\nname: test\ndescription: \"x\"\n---\n\nBody text here.";
        assertEquals("Body text here.", PromptLoader.stripFrontmatter(input));
    }

    @Test
    public void stripFrontmatter_passesThroughWhenNoFrontmatter() {
        var input = "Plain prompt with no frontmatter.";
        assertSame(input, PromptLoader.stripFrontmatter(input));
    }

    @Test
    public void stripFrontmatter_passesThroughOnMissingClosingFence() {
        // Malformed: opening --- but no closing --- — keep content rather than
        // silently drop the body.
        var input = "---\nname: test\nbody never starts";
        assertEquals(input, PromptLoader.stripFrontmatter(input));
    }

    @Test
    public void stripFrontmatter_handlesNullAndEmpty() {
        assertNull(PromptLoader.stripFrontmatter(null));
        assertEquals("", PromptLoader.stripFrontmatter(""));
        assertEquals("hi", PromptLoader.stripFrontmatter("hi"));
    }

    @Test
    public void stripFrontmatter_doesNotTreatHorizontalRuleAsFence() {
        // A markdown horizontal rule mid-body must not be mistaken for a closing
        // fence — but since the file does not start with ---, the whole content
        // passes through.
        var input = "Intro paragraph.\n\n---\n\nNext section.";
        assertSame(input, PromptLoader.stripFrontmatter(input));
    }

    @Test
    public void stripFrontmatter_preservesDashedRuleInsideBody() {
        var input = "---\nname: test\n---\n\nIntro\n\n---\n\nMore body.";
        assertEquals("Intro\n\n---\n\nMore body.", PromptLoader.stripFrontmatter(input));
    }

    @Test
    public void loadSkill_returnsBodyOnlyForSpecCompliantFixture() {
        var content = PromptLoader.loadSkill("spec-test-with-frontmatter");
        assertNotNull(content);
        assertFalse(content.contains("---"), "frontmatter fence must not leak: " + content);
        assertFalse(content.contains("name:"), "frontmatter keys must not leak: " + content);
        assertTrue(content.startsWith("You are a fixture skill"), "body preserved: " + content);
    }

    @Test
    public void loadSkill_returnsBareContentUnchanged() {
        var content = PromptLoader.loadSkill("spec-test-without-frontmatter");
        assertEquals("You are a fixture skill without frontmatter.", content);
    }
}
