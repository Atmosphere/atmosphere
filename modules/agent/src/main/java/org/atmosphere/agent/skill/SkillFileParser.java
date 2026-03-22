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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a skill markdown file into its component sections. The entire file
 * content serves as the system prompt verbatim, while individual sections
 * are extracted for protocol metadata (A2A skills, tool cross-referencing, etc.).
 *
 * <p>Expected format:</p>
 * <pre>
 * # Agent Title
 *
 * Description paragraph.
 *
 * ## Skills
 * - Skill one description
 * - Skill two description
 *
 * ## Tools
 * - tool_name: description
 *
 * ## Channels
 * - slack
 * - telegram
 *
 * ## Guardrails
 * - No medical advice
 * </pre>
 */
public final class SkillFileParser {

    private final String rawContent;
    private final String title;
    private final Map<String, String> sections;

    private SkillFileParser(String rawContent, String title, Map<String, String> sections) {
        this.rawContent = rawContent;
        this.title = title;
        this.sections = sections;
    }

    /**
     * Parses the given markdown content.
     *
     * @param content the full skill file content
     * @return a parsed skill file
     */
    public static SkillFileParser parse(String content) {
        if (content == null || content.isBlank()) {
            return new SkillFileParser("", "", Map.of());
        }

        String title = "";
        Map<String, String> sections = new LinkedHashMap<>();
        String currentSection = null;
        var sectionContent = new StringBuilder();
        var lines = content.split("\n");
        boolean inCodeBlock = false;

        for (var line : lines) {
            var trimmedLine = line.trim();

            // Track fenced code blocks — skip header detection inside them
            if (trimmedLine.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (currentSection != null) {
                    sectionContent.append(line).append("\n");
                }
                continue;
            }

            if (inCodeBlock) {
                if (currentSection != null) {
                    sectionContent.append(line).append("\n");
                }
                continue;
            }

            if (line.startsWith("# ") && title.isEmpty() && !line.startsWith("## ")) {
                title = line.substring(2).trim();
                continue;
            }

            if (line.startsWith("## ")) {
                if (currentSection != null) {
                    sections.put(currentSection, sectionContent.toString().trim());
                }
                currentSection = line.substring(3).trim();
                sectionContent.setLength(0);
                continue;
            }

            if (currentSection != null) {
                sectionContent.append(line).append("\n");
            }
        }

        if (currentSection != null) {
            sections.put(currentSection, sectionContent.toString().trim());
        }

        return new SkillFileParser(content, title,
                Collections.unmodifiableMap(new LinkedHashMap<>(sections)));
    }

    /**
     * Returns the entire file content, used as the system prompt verbatim.
     */
    public String systemPrompt() {
        return rawContent;
    }

    /**
     * Returns the top-level title (from {@code # Title}), used as a fallback agent name.
     */
    public String title() {
        return title;
    }

    /**
     * Returns the content of a named section.
     *
     * @param name the section name (e.g. "Skills", "Tools")
     * @return the section content, or empty if not found
     */
    public Optional<String> section(String name) {
        return Optional.ofNullable(sections.get(name));
    }

    /**
     * Returns all section names in document order.
     */
    public List<String> sectionNames() {
        return List.copyOf(sections.keySet());
    }

    /**
     * Extracts bullet-point items from a section as a list of strings.
     * Each item is a line starting with "- " (with the prefix stripped).
     *
     * @param sectionName the section to extract from
     * @return list of items, or empty list if section not found
     */
    public List<String> listItems(String sectionName) {
        var content = sections.get(sectionName);
        if (content == null || content.isBlank()) {
            return List.of();
        }
        var items = new ArrayList<String>();
        for (var line : content.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                items.add(trimmed.substring(2).trim());
            }
        }
        return List.copyOf(items);
    }

    /**
     * Returns whether the skill file has any content.
     */
    public boolean isEmpty() {
        return rawContent.isBlank();
    }
}
