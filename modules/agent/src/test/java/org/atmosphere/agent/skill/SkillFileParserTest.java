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
package org.atmosphere.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SkillFileParserTest {

    private static final String FULL_SKILL_FILE = """
            # Customer Support Agent

            You are a helpful customer support assistant.
            Always be polite and professional.

            ## Skills
            - Answer product questions
            - Process refunds
            - Track orders

            ## Tools
            - order_lookup: Look up order by ID
            - refund_processor: Process a refund

            ## Channels
            - slack
            - telegram

            ## Guardrails
            - No medical advice
            - No financial advice
            - Always escalate complaints
            """;

    @Test
    public void testParseTitle() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertEquals("Customer Support Agent", parsed.title());
    }

    @Test
    public void testSystemPromptIsFullContent() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertEquals(FULL_SKILL_FILE, parsed.systemPrompt());
    }

    @Test
    public void testSectionNames() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertEquals(List.of("Skills", "Tools", "Channels", "Guardrails"), parsed.sectionNames());
    }

    @Test
    public void testSection() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        var skills = parsed.section("Skills");
        assertTrue(skills.isPresent());
        assertTrue(skills.get().contains("Answer product questions"));
        assertTrue(skills.get().contains("Process refunds"));
    }

    @Test
    public void testMissingSection() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertTrue(parsed.section("NonExistent").isEmpty());
    }

    @Test
    public void testListItems() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        var skills = parsed.listItems("Skills");
        assertEquals(3, skills.size());
        assertEquals("Answer product questions", skills.get(0));
        assertEquals("Process refunds", skills.get(1));
        assertEquals("Track orders", skills.get(2));
    }

    @Test
    public void testListItemsTools() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        var tools = parsed.listItems("Tools");
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).startsWith("order_lookup:"));
    }

    @Test
    public void testListItemsChannels() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        var channels = parsed.listItems("Channels");
        assertEquals(List.of("slack", "telegram"), channels);
    }

    @Test
    public void testListItemsNonExistent() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertEquals(List.of(), parsed.listItems("NonExistent"));
    }

    @Test
    public void testEmptyContent() {
        var parsed = SkillFileParser.parse("");
        assertTrue(parsed.isEmpty());
        assertEquals("", parsed.title());
        assertEquals("", parsed.systemPrompt());
        assertEquals(List.of(), parsed.sectionNames());
    }

    @Test
    public void testNullContent() {
        var parsed = SkillFileParser.parse(null);
        assertTrue(parsed.isEmpty());
    }

    @Test
    public void testTitleOnly() {
        var parsed = SkillFileParser.parse("# My Agent\n\nJust a description.");
        assertEquals("My Agent", parsed.title());
        assertFalse(parsed.isEmpty());
        assertEquals(List.of(), parsed.sectionNames());
    }

    @Test
    public void testNoTitle() {
        var content = "## Skills\n- Skill one\n";
        var parsed = SkillFileParser.parse(content);
        assertEquals("", parsed.title());
        assertEquals(List.of("Skills"), parsed.sectionNames());
        assertEquals(List.of("Skill one"), parsed.listItems("Skills"));
    }

    @Test
    public void testGuardrailsSection() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        var guardrails = parsed.listItems("Guardrails");
        assertEquals(3, guardrails.size());
        assertEquals("No medical advice", guardrails.get(0));
        assertEquals("Always escalate complaints", guardrails.get(2));
    }

    @Test
    public void testNotIsEmpty() {
        var parsed = SkillFileParser.parse(FULL_SKILL_FILE);
        assertFalse(parsed.isEmpty());
    }
}
