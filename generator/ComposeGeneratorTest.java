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
///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ComposeGenerator.java
//DEPS info.picocli:picocli:4.7.6
//DEPS com.samskivert:jmustache:1.16
//DEPS org.junit.jupiter:junit-jupiter-api:5.11.4
//DEPS org.junit.jupiter:junit-jupiter-engine:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//JAVA 21

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Unit tests for ComposeGenerator — skill parsing, workflow DSL, model building.
 *
 * Run with: jbang generator/ComposeGeneratorTest.java
 */
public class ComposeGeneratorTest {

    public static void main(String... args) {
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ComposeGeneratorTest.class))
                .build();
        var launcher = LauncherFactory.create();
        var listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));

        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }

    private ComposeGenerator gen() {
        var g = new ComposeGenerator();
        g.name = "test-team";
        g.groupId = "com.example";
        g.protocol = "a2a";
        g.transport = "websocket";
        g.frontend = "none";
        g.aiFramework = "builtin";
        g.deploy = "docker-compose";
        g.atmosphereVersionOverride = "4.0.29-SNAPSHOT";
        g.springBootVersionOverride = "4.0.5";
        g.scriptDir = Path.of("").toAbsolutePath();
        return g;
    }

    // ========== Skill File Parsing ==========

    @Test
    void parseSkillFile_extractsNameFromFrontmatter(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("test.md");
        Files.writeString(skill, """
                ---
                name: my-agent
                description: "A test agent"
                ---
                # My Agent
                You are helpful.
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals("my-agent", desc.name);
        assertEquals("A test agent", desc.description);
        assertEquals("agent", desc.role);
    }

    @Test
    void parseSkillFile_detectsCoordinatorByRole(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("coord.md");
        Files.writeString(skill, """
                ---
                name: boss
                description: "The coordinator"
                role: coordinator
                ---
                # Boss
                You coordinate.
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals("boss", desc.name);
        assertEquals("coordinator", desc.role);
    }

    @Test
    void parseSkillFile_detectsCoordinatorByFleetSection(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("lead.md");
        Files.writeString(skill, """
                ---
                name: team-lead
                description: "Leads the team"
                ---
                # Team Lead

                ## Fleet
                - agent-a: Does A
                - agent-b: Does B
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals("coordinator", desc.role);
    }

    @Test
    void parseSkillFile_fallsBackToFilename(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("my-cool-agent.md");
        Files.writeString(skill, "# My Cool Agent\nYou are cool.");
        var desc = gen().parseSkillFile(skill);
        assertEquals("my-cool-agent", desc.name);
    }

    @Test
    void parseSkillFile_fallsBackToParentDirForSKILL(@TempDir Path tmp) throws IOException {
        var dir = tmp.resolve("researcher");
        Files.createDirectories(dir);
        var skill = dir.resolve("SKILL.md");
        Files.writeString(skill, "# Researcher\nYou research.");
        var desc = gen().parseSkillFile(skill);
        assertEquals("researcher", desc.name);
    }

    @Test
    void parseSkillFile_generatesClassName(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("test.md");
        Files.writeString(skill, """
                ---
                name: market-research-agent
                description: "Researches markets"
                ---
                # Market Research Agent
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals("MarketResearchAgent", desc.className);
    }

    // ========== Tool Parsing ==========

    @Test
    void parseSkillFile_parsesToolsWithColon(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("tools.md");
        Files.writeString(skill, """
                ---
                name: tooled
                description: "Has tools"
                ---
                # Tooled Agent

                ## Tools
                - search_web: Search the web for data
                - read_file: Read a file from disk

                ## Guardrails
                - Be careful
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals(2, desc.tools.size());
        assertEquals("search_web", desc.tools.get(0).name());
        assertEquals("Search the web for data", desc.tools.get(0).description());
        assertEquals("searchWeb", desc.tools.get(0).methodName());
        assertEquals("read_file", desc.tools.get(1).name());
        assertEquals("readFile", desc.tools.get(1).methodName());
    }

    @Test
    void parseSkillFile_parsesToolsWithBoldMarkdown(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("bold.md");
        Files.writeString(skill, """
                ---
                name: bold-tools
                description: "Bold tool names"
                ---
                # Agent

                ## Tools
                - **get_weather**: Get weather for a city
                - **check_time**: Check the current time
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals(2, desc.tools.size());
        assertEquals("get_weather", desc.tools.get(0).name());
        assertEquals("getWeather", desc.tools.get(0).methodName());
    }

    @Test
    void parseSkillFile_parsesToolsWithDash(@TempDir Path tmp) throws IOException {
        var skill = tmp.resolve("dash.md");
        Files.writeString(skill, """
                ---
                name: dash-tools
                description: "Dash-separated tools"
                ---
                # Agent

                ## Tools
                - search_codebase -- Search for code patterns
                """);
        var desc = gen().parseSkillFile(skill);
        assertEquals(1, desc.tools.size());
        assertEquals("search_codebase", desc.tools.get(0).name());
        assertEquals("Search for code patterns", desc.tools.get(0).description());
    }

    // ========== Workflow DSL Parsing ==========

    @Test
    void parseWorkflow_sequentialSteps() {
        var content = """
                # Coordinator

                ## Workflow
                researcher -> strategist -> writer

                ## Guardrails
                """;
        var steps = gen().parseWorkflow(content);
        assertEquals(3, steps.size());
        assertEquals(List.of("researcher"), steps.get(0).agentNames());
        assertEquals(List.of("strategist"), steps.get(1).agentNames());
        assertEquals(List.of("writer"), steps.get(2).agentNames());
        assertFalse(steps.get(0).isParallel());
    }

    @Test
    void parseWorkflow_parallelSteps() {
        var content = """
                # Coordinator

                ## Workflow
                researcher -> strategist, finance -> writer
                """;
        var steps = gen().parseWorkflow(content);
        assertEquals(3, steps.size());
        assertEquals(List.of("researcher"), steps.get(0).agentNames());
        assertEquals(List.of("strategist", "finance"), steps.get(1).agentNames());
        assertTrue(steps.get(1).isParallel());
        assertEquals(List.of("writer"), steps.get(2).agentNames());
    }

    @Test
    void parseWorkflow_emptySection() {
        var content = "# Coordinator\n\n## Skills\n- Coordinate";
        var steps = gen().parseWorkflow(content);
        assertTrue(steps.isEmpty());
    }

    // ========== Section Extraction ==========

    @Test
    void extractSection_findsSection() {
        var content = """
                # Title

                ## Skills
                - Skill one
                - Skill two

                ## Tools
                - tool_a: Does A

                ## Guardrails
                - Be safe
                """;
        var tools = gen().extractSection(content, "Tools");
        assertNotNull(tools);
        assertTrue(tools.contains("tool_a: Does A"));
        assertFalse(tools.contains("Skill one"));
        assertFalse(tools.contains("Be safe"));
    }

    @Test
    void extractSection_returnsNullForMissing() {
        var content = "# Title\n\n## Skills\n- One";
        var result = gen().extractSection(content, "NonExistent");
        assertTrue(result == null || result.isBlank());
    }

    // ========== Model Building ==========

    @Test
    void buildModel_setsProtocolBooleans(@TempDir Path tmp) throws IOException {
        var g = gen();
        setupMinimalSkills(g, tmp);
        var model = g.buildModel();
        assertEquals(true, model.get("isA2a"));
        assertEquals(false, model.get("isLocal"));
    }

    @Test
    void buildModel_setsDeploymentBooleans(@TempDir Path tmp) throws IOException {
        var g = gen();
        setupMinimalSkills(g, tmp);
        var model = g.buildModel();
        assertEquals(true, model.get("isDockerCompose"));
        assertEquals(false, model.get("isSingleJar"));
    }

    @Test
    void buildModel_setsAiFrameworkBooleans(@TempDir Path tmp) throws IOException {
        var g = gen();
        setupMinimalSkills(g, tmp);
        var model = g.buildModel();
        assertEquals(true, model.get("isBuiltin"));
        assertEquals(false, model.get("isSpringAi"));
    }

    @Test
    void buildModel_includesCoordinatorAndAgents(@TempDir Path tmp) throws IOException {
        var g = gen();
        setupMinimalSkills(g, tmp);
        var model = g.buildModel();
        assertNotNull(model.get("coordinator"));
        var agents = (List<?>) model.get("agents");
        assertEquals(2, agents.size());
    }

    @Test
    void buildModel_usesVersionOverrides(@TempDir Path tmp) throws IOException {
        var g = gen();
        g.atmosphereVersionOverride = "99.0.0";
        g.springBootVersionOverride = "88.0.0";
        setupMinimalSkills(g, tmp);
        var model = g.buildModel();
        assertEquals("99.0.0", model.get("atmosphereVersion"));
        assertEquals("88.0.0", model.get("springBootVersion"));
    }

    // ========== Auto-Generated Coordinator ==========

    @Test
    void generateDefaultCoordinator_createsFleetSection(@TempDir Path tmp) throws IOException {
        var g = gen();
        var agent1 = new ComposeGenerator.SkillDescriptor();
        agent1.name = "alpha";
        agent1.description = "Agent Alpha";
        var agent2 = new ComposeGenerator.SkillDescriptor();
        agent2.name = "beta";
        agent2.description = "Agent Beta";
        var coord = g.generateDefaultCoordinator(List.of(agent1, agent2));
        assertEquals("coordinator", coord.role);
        assertTrue(coord.content.contains("## Fleet"));
        assertTrue(coord.content.contains("- alpha: Agent Alpha"));
        assertTrue(coord.content.contains("- beta: Agent Beta"));
        assertTrue(coord.content.contains("## Workflow"));
    }

    // ========== E2E: Full Project Generation ==========

    @Test
    void generateProject_createsExpectedFiles(@TempDir Path tmp) throws IOException {
        var skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir);

        Files.writeString(skillsDir.resolve("coordinator.md"), """
                ---
                name: lead
                description: "Team lead"
                role: coordinator
                ---
                # Lead

                ## Fleet
                - worker: Does work

                ## Workflow
                worker
                """);
        Files.writeString(skillsDir.resolve("worker.md"), """
                ---
                name: worker
                description: "Worker agent"
                ---
                # Worker

                ## Tools
                - do_task: Execute a task
                """);

        var g = gen();
        g.scriptDir = Path.of("").toAbsolutePath();
        g.allSkills.add(g.parseSkillFile(skillsDir.resolve("coordinator.md")));
        g.allSkills.add(g.parseSkillFile(skillsDir.resolve("worker.md")));

        // Categorize
        for (var desc : g.allSkills) {
            if ("coordinator".equals(desc.role)) g.coordinator = desc;
            else g.agents.add(desc);
        }
        g.workflowSteps = g.parseWorkflow(g.coordinator.content);
        g.coordinator.port = 8080;
        g.agents.get(0).port = 8081;

        var outputDir = tmp.resolve("output");
        var model = g.buildModel();
        g.generateProject(model, outputDir);

        // Verify directory structure
        assertTrue(Files.exists(outputDir.resolve("pom.xml")), "parent POM");
        assertTrue(Files.exists(outputDir.resolve("coordinator/pom.xml")), "coordinator POM");
        assertTrue(Files.exists(outputDir.resolve("agents/worker/pom.xml")), "worker POM");
        assertTrue(Files.exists(outputDir.resolve("docker-compose.yml")), "docker-compose");
        assertTrue(Files.exists(outputDir.resolve("Dockerfile")), "Dockerfile");
        assertTrue(Files.exists(outputDir.resolve("README.md")), "README");

        // Verify coordinator Java file
        var coordJava = findFile(outputDir.resolve("coordinator"), "Lead.java");
        assertNotNull(coordJava, "Lead.java should exist");
        var coordContent = Files.readString(coordJava);
        assertTrue(coordContent.contains("@Coordinator"), "has @Coordinator");
        assertTrue(coordContent.contains("@Fleet"), "has @Fleet");
        assertTrue(coordContent.contains("@AgentRef"), "has @AgentRef");
        assertTrue(coordContent.contains("AgentFleet fleet"), "has AgentFleet param");

        // Verify agent Java file
        var agentJava = findFile(outputDir.resolve("agents/worker"), "Worker.java");
        assertNotNull(agentJava, "Worker.java should exist");
        var agentContent = Files.readString(agentJava);
        assertTrue(agentContent.contains("@Agent"), "has @Agent");
        assertTrue(agentContent.contains("@Prompt"), "has @Prompt");
        assertTrue(agentContent.contains("session.stream(message)"), "delegates to LLM");
        assertTrue(agentContent.contains("@AiTool"), "has @AiTool for do_task");
        assertTrue(agentContent.contains("doTask"), "has camelCase method name");

        // Verify skill files copied
        assertTrue(Files.exists(outputDir.resolve("coordinator/src/main/resources/prompts/skill.md")));
        assertTrue(Files.exists(outputDir.resolve("agents/worker/src/main/resources/prompts/skill.md")));

        // Verify docker-compose contains both services
        var compose = Files.readString(outputDir.resolve("docker-compose.yml"));
        assertTrue(compose.contains("coordinator:"), "has coordinator service");
        assertTrue(compose.contains("worker:"), "has worker service");
        assertTrue(compose.contains("8081"), "has worker port");

        // Verify parent POM modules
        var parentPom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(parentPom.contains("<module>coordinator</module>"));
        assertTrue(parentPom.contains("<module>agents/worker</module>"));
    }

    // ========== Utility Tests ==========

    @Test
    void toPascalCase_convertsKebab() {
        assertEquals("MyAgent", ComposeGenerator.toPascalCase("my-agent"));
        assertEquals("Research", ComposeGenerator.toPascalCase("research"));
        assertEquals("A", ComposeGenerator.toPascalCase("a"));
    }

    @Test
    void toCamelCase_convertsUnderscores() {
        assertEquals("searchWeb", ComposeGenerator.toCamelCase("search_web"));
        assertEquals("get", ComposeGenerator.toCamelCase("get"));
        assertEquals("getWeather", ComposeGenerator.toCamelCase("get_weather"));
    }

    // ========== Helpers ==========

    private void setupMinimalSkills(ComposeGenerator g, Path tmp) throws IOException {
        var skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("coord.md"), """
                ---
                name: coord
                description: "Coordinator"
                role: coordinator
                ---
                # Coordinator
                ## Fleet
                - a: Agent A
                - b: Agent B
                ## Workflow
                a -> b
                """);
        Files.writeString(skillsDir.resolve("a.md"), "---\nname: a\ndescription: A\n---\n# A");
        Files.writeString(skillsDir.resolve("b.md"), "---\nname: b\ndescription: B\n---\n# B");

        g.allSkills.add(g.parseSkillFile(skillsDir.resolve("coord.md")));
        g.allSkills.add(g.parseSkillFile(skillsDir.resolve("a.md")));
        g.allSkills.add(g.parseSkillFile(skillsDir.resolve("b.md")));

        for (var desc : g.allSkills) {
            if ("coordinator".equals(desc.role)) g.coordinator = desc;
            else g.agents.add(desc);
        }
        g.workflowSteps = g.parseWorkflow(g.coordinator.content);
        g.coordinator.port = 8080;
        for (int i = 0; i < g.agents.size(); i++) {
            g.agents.get(i).port = 8081 + i;
        }
    }

    private Path findFile(Path dir, String name) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst().orElse(null);
        }
    }
}
