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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
    public void testFrontmatterExcludedFromSystemPromptButParsed() {
        var withFrontmatter = """
                ---
                description: "Order-status support"
                scopeTier: rule_based
                ---
                # Support Bot

                ## Guardrails
                - gambling
                """;
        var parsed = SkillFileParser.parse(withFrontmatter);
        // Raw-loaded content (PromptLoader.resolveRaw) keeps the frontmatter so
        // hints like scopeTier work — but it must never leak into the LLM prompt.
        assertFalse(parsed.systemPrompt().contains("scopeTier"),
                "YAML frontmatter must not leak into the LLM system prompt");
        assertTrue(parsed.systemPrompt().startsWith("# Support Bot"),
                "the prompt body must start after the frontmatter block");
        assertEquals("rule_based", parsed.frontmatter("scopeTier").orElseThrow());
        assertEquals("Order-status support", parsed.frontmatter("description").orElseThrow());
        assertEquals(List.of("gambling"), parsed.listItems("Guardrails"));
        assertEquals("Support Bot", parsed.title());
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

    /**
     * Every {@code ## Tools} bullet form the samples, the skill catalog and
     * {@code atmosphere import} use. Splitting on ':' alone turned the
     * backticked-with-arguments and em-dash forms into the whole line, so each
     * boot warned about a tool that was registered and fired.
     */
    @Test
    public void toolNameUnderstandsEveryBulletForm() {
        assertEquals("get_weather", SkillFileParser.toolName("get_weather: Get current weather").orElseThrow());
        assertEquals("get_data", SkillFileParser.toolName("**get_data**: Fetch data from API").orElseThrow());
        assertEquals("check_status", SkillFileParser.toolName("`check_status`: Check system status").orElseThrow());
        assertEquals("get_weather", SkillFileParser.toolName(
                "`get_weather(city)` \u2014 returns a short weather report for a city.").orElseThrow());
        assertEquals("get_time", SkillFileParser.toolName(
                "`get_time(timezone)` \u2014 returns the current time for an IANA timezone").orElseThrow());
        assertEquals("convert_units", SkillFileParser.toolName(
                "convert_units \u2014 Convert between Celsius and Fahrenheit").orElseThrow());
        assertEquals("convert_units", SkillFileParser.toolName(
                "convert_units - Convert between Celsius and Fahrenheit").orElseThrow());
        assertEquals("search-web", SkillFileParser.toolName("search-web: Search the web").orElseThrow());
        assertEquals("lookup", SkillFileParser.toolName("lookup").orElseThrow());
        assertTrue(SkillFileParser.toolName("\u2014 no name here").isEmpty());
        assertTrue(SkillFileParser.toolName(null).isEmpty());
    }

    /**
     * The samples' skill files must name tools their own {@code @AiTool}
     * methods register — the cross-reference the runtime warns about at boot.
     */
    @Test
    public void sampleSkillFilesNameToolsTheirSamplesRegister() throws IOException {
        var samples = Path.of("../../samples").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(samples), "samples directory: " + samples);
        var checked = 0;
        try (var sampleDirs = Files.list(samples)) {
            for (var sample : sampleDirs.sorted().toList()) {
                var resources = sample.resolve("src/main/resources");
                var sources = sample.resolve("src/main/java");
                if (!Files.isDirectory(resources) || !Files.isDirectory(sources)) {
                    continue;
                }
                var registered = registeredToolNames(sources);
                try (var files = Files.walk(resources)) {
                    for (var md : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                        for (var item : SkillFileParser.parse(Files.readString(md)).listItems("Tools")) {
                            var name = SkillFileParser.toolName(item);
                            assertTrue(name.isPresent(), md + ": no tool name in '" + item + "'");
                            if (registered.isEmpty()) {
                                continue;    // the sample registers no @AiTool of its own
                            }
                            assertTrue(registered.contains(name.get()), md + " declares '"
                                    + name.get() + "' but the sample registers " + registered);
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 6, "expected the agui and orchestration skill tools, checked " + checked);
    }

    private static Set<String> registeredToolNames(Path sources) throws IOException {
        var pattern = Pattern.compile("@AiTool\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
        var names = new HashSet<String>();
        try (var files = Files.walk(sources)) {
            for (var source : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(source));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }
}
