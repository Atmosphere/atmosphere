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
//DEPS info.picocli:picocli:4.7.6
//DEPS com.samskivert:jmustache:1.16
//JAVA 21

import com.samskivert.mustache.Mustache;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Command(name = "atmosphere-compose",
        mixinStandardHelpOptions = true,
        version = "atmosphere-compose 1.0",
        description = "Generate a multi-agent Atmosphere project from skill files")
public class ComposeGenerator implements Runnable {

    /** Sanitize a name for safe use as a filesystem path component. */
    static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        // Strip path traversal, separators, and control chars
        return raw.replaceAll("[/\\\\]", "-")
                  .replace("..", "")
                  .replaceAll("[^a-zA-Z0-9._-]", "-")
                  .replaceAll("-{2,}", "-")
                  .replaceAll("^-|-$", "");
    }

    @Option(names = {"-n", "--name"}, description = "Project name (e.g. my-multi-agent)")
    String name;

    @Option(names = {"-g", "--group"}, defaultValue = "com.example", description = "Group ID")
    String groupId;

    @Option(names = {"--protocol"}, description = "Agent communication protocol: a2a, local")
    String protocol;

    @Option(names = {"--transport"}, description = "Client transport: websocket, sse")
    String transport;

    @Option(names = {"--frontend"}, description = "Frontend type: react, none")
    String frontend;

    @Option(names = {"--ai"}, description = "AI framework: builtin, langchain4j, spring-ai, adk")
    String aiFramework;

    @Option(names = {"--deploy"}, description = "Deployment mode: docker-compose, single-jar")
    String deploy;

    @Option(names = {"--skills"}, description = "Comma-separated list of skill file paths")
    String skillPaths;

    @Option(names = {"-o", "--output"}, description = "Output directory")
    Path output;

    @Option(names = {"--atmosphere-version"}, description = "Atmosphere version (overrides POM detection)")
    String atmosphereVersionOverride;

    @Option(names = {"--spring-boot-version"}, description = "Spring Boot version (overrides POM detection)")
    String springBootVersionOverride;

    @Parameters(description = "Skill file paths (positional)")
    List<Path> positionalSkills;

    Path scriptDir;

    // ── Descriptors ──────────────────────────────────────────────────────────

    static class SkillDescriptor {
        String name;
        String description;
        String role; // "coordinator" or "agent"
        String className;
        String packageSuffix;
        String content;
        Path sourcePath;
        List<ToolDescriptor> tools = new ArrayList<>();
        int port;
    }

    record ToolDescriptor(String name, String description, String methodName) {}

    record WorkflowStep(List<String> agentNames) {
        boolean isParallel() {
            return agentNames.size() > 1;
        }
    }

    // ── Parsed state ─────────────────────────────────────────────────────────

    List<SkillDescriptor> allSkills = new ArrayList<>();
    SkillDescriptor coordinator;
    List<SkillDescriptor> agents = new ArrayList<>();
    List<WorkflowStep> workflowSteps = new ArrayList<>();

    public static void main(String... args) {
        int exitCode = new CommandLine(new ComposeGenerator()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        scriptDir = resolveScriptDir();

        // Collect skill paths from both --skills and positional parameters
        var paths = new ArrayList<Path>();
        if (skillPaths != null && !skillPaths.isBlank()) {
            for (var p : skillPaths.split(",")) {
                paths.add(Path.of(p.trim()));
            }
        }
        if (positionalSkills != null) {
            paths.addAll(positionalSkills);
        }

        // Parse all skill files
        for (var path : paths) {
            allSkills.add(parseSkillFile(path));
        }

        // Prompt for missing options
        promptForMissing();
        validate();

        // Separate coordinator from agents
        for (var skill : allSkills) {
            if ("coordinator".equals(skill.role)) {
                coordinator = skill;
            } else {
                agents.add(skill);
            }
        }

        // Auto-generate coordinator if none provided
        if (coordinator == null) {
            coordinator = generateDefaultCoordinator(agents);
        }

        // Assign ports: coordinator=8080, agents=8081, 8082, ...
        coordinator.port = 8080;
        for (int i = 0; i < agents.size(); i++) {
            agents.get(i).port = 8081 + i;
        }

        // Parse workflow from coordinator skill content
        if (coordinator.content != null) {
            workflowSteps = parseWorkflow(coordinator.content);
        }
        if (workflowSteps.isEmpty()) {
            // Default: all agents sequential
            for (var agent : agents) {
                workflowSteps.add(new WorkflowStep(List.of(agent.name)));
            }
        }

        var model = buildModel();
        var outputDir = output != null ? output : Path.of(name);

        try {
            generateProject(model, outputDir);
            System.out.println();
            System.out.println("Project generated in: " + outputDir.toAbsolutePath());
            System.out.println();
            System.out.println("Next steps:");
            if ("docker-compose".equals(deploy)) {
                System.out.println("  cd " + outputDir);
                System.out.println("  docker-compose up --build");
            } else {
                System.out.println("  cd " + outputDir);
                System.out.println("  ./mvnw install");
                System.out.println("  java -jar coordinator/target/*.jar");
            }
            System.out.println("  open http://localhost:8080");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate project", e);
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    SkillDescriptor parseSkillFile(Path path) {
        var desc = new SkillDescriptor();
        desc.sourcePath = path.toAbsolutePath();
        try {
            desc.content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read skill file: " + path, e);
        }

        // Parse YAML frontmatter
        if (desc.content.startsWith("---")) {
            var endIdx = desc.content.indexOf("---", 3);
            if (endIdx > 0) {
                var frontmatter = desc.content.substring(3, endIdx).trim();
                desc.name = extractYamlField(frontmatter, "name");
                desc.description = extractYamlField(frontmatter, "description");
                desc.role = extractYamlField(frontmatter, "role");
            }
        }

        // Detect coordinator by role or ## Fleet section
        if (desc.role == null || desc.role.isBlank()) {
            if (desc.content.contains("## Fleet")) {
                desc.role = "coordinator";
            } else {
                desc.role = "agent";
            }
        }

        // Fallback name from filename
        if (desc.name == null || desc.name.isBlank()) {
            var fileName = path.getFileName().toString();
            var parentName = path.getParent() != null && path.getParent().getFileName() != null
                    ? path.getParent().getFileName().toString() : "skill";
            desc.name = fileName.replaceAll("\\.(md|yaml|yml)$", "")
                    .replaceAll("^SKILL$", parentName);
        }

        if (desc.description == null || desc.description.isBlank()) {
            desc.description = "Agent for " + desc.name;
        }

        // Generate className from name (kebab-case to PascalCase)
        desc.className = toPascalCase(desc.name);
        desc.packageSuffix = desc.name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        // Parse ## Tools section
        // Supports: - tool_name: description, - **tool_name**: description, - tool_name — description
        var toolsSection = extractSection(desc.content, "Tools");
        if (toolsSection != null && !toolsSection.isBlank()) {
            var lines = toolsSection.lines().toList();
            for (var line : lines) {
                var trimmed = line.trim();
                if (!trimmed.startsWith("- ")) continue;
                trimmed = trimmed.substring(2).replaceAll("\\*\\*", ""); // strip markdown bold

                String toolName, toolDesc;
                if (trimmed.contains(":")) {
                    var idx = trimmed.indexOf(':');
                    toolName = trimmed.substring(0, idx).trim();
                    toolDesc = trimmed.substring(idx + 1).trim();
                } else if (trimmed.contains(" — ") || trimmed.contains(" -- ")) {
                    var sep = trimmed.contains(" — ") ? " — " : " -- ";
                    var idx = trimmed.indexOf(sep);
                    toolName = trimmed.substring(0, idx).trim();
                    toolDesc = trimmed.substring(idx + sep.length()).trim();
                } else {
                    toolName = trimmed;
                    toolDesc = trimmed;
                }

                toolName = toolName.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                        .replaceAll("_+", "_").replaceAll("^_|_$", "");
                if (toolName.isEmpty()) continue;

                var methodName = toCamelCase(toolName);
                desc.tools.add(new ToolDescriptor(toolName, toolDesc, methodName));
            }
        }

        return desc;
    }

    List<WorkflowStep> parseWorkflow(String content) {
        var steps = new ArrayList<WorkflowStep>();
        var section = extractSection(content, "Workflow");
        if (section == null || section.isBlank()) {
            return steps;
        }

        // DSL: comma = parallel, arrow (→ or ->) = sequential
        // Example: "research, analyze -> summarize -> review"
        var dslLine = section.lines()
                .filter(l -> l.contains("->") || l.contains("\u2192") || l.contains(","))
                .findFirst()
                .orElse(null);

        if (dslLine == null) {
            // Try line-by-line: each non-empty line is a sequential step
            section.lines()
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .forEach(l -> {
                        var names = List.of(l.split(","));
                        steps.add(new WorkflowStep(
                                names.stream().map(String::trim).filter(n -> !n.isBlank()).toList()));
                    });
            return steps;
        }

        // Split by arrow for sequential steps
        var seqParts = dslLine.split("->|\u2192");
        for (var part : seqParts) {
            var names = List.of(part.split(","));
            steps.add(new WorkflowStep(
                    names.stream().map(String::trim).filter(n -> !n.isBlank()).toList()));
        }

        return steps;
    }

    SkillDescriptor generateDefaultCoordinator(List<SkillDescriptor> agentList) {
        var desc = new SkillDescriptor();
        desc.name = name != null ? name + "-coordinator" : "coordinator";
        desc.description = "Coordinates the " + (name != null ? name : "agent") + " fleet";
        desc.role = "coordinator";
        desc.className = toPascalCase(desc.name);
        desc.packageSuffix = desc.name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        var sb = new StringBuilder();
        sb.append("---\nname: ").append(desc.name);
        sb.append("\ndescription: \"").append(desc.description).append("\"");
        sb.append("\nrole: coordinator\n---\n\n");
        sb.append("# ").append(desc.className).append("\n\n");
        sb.append("You coordinate a fleet of specialist agents.\n\n");
        sb.append("## Fleet\n");
        for (var agent : agentList) {
            sb.append("- ").append(agent.name).append(": ").append(agent.description).append("\n");
        }
        sb.append("\n## Workflow\n");
        sb.append(agentList.stream().map(a -> a.name).collect(java.util.stream.Collectors.joining(" -> ")));
        sb.append("\n");

        desc.content = sb.toString();
        desc.sourcePath = null;
        return desc;
    }

    // ── Interactive prompts ──────────────────────────────────────────────────

    private void promptForMissing() {
        if (name == null || name.isBlank()) {
            name = prompt("Project name", "my-multi-agent");
        }
        if (protocol == null || protocol.isBlank()) {
            System.out.println();
            System.out.println("Agent communication protocol:");
            System.out.println("  1) a2a       -- A2A protocol (HTTP JSON-RPC, each agent is a service)");
            System.out.println("  2) local     -- In-process (single JVM, direct method calls)");
            var choice = prompt("Choose protocol [1-2]", "1");
            protocol = switch (choice) {
                case "2", "local" -> "local";
                default -> "a2a";
            };
        }
        if (transport == null || transport.isBlank()) {
            System.out.println();
            System.out.println("Client transport:");
            System.out.println("  1) websocket -- Full-duplex WebSocket connection");
            System.out.println("  2) sse       -- Server-Sent Events (HTTP streaming)");
            var choice = prompt("Choose transport [1-2]", "1");
            transport = switch (choice) {
                case "2", "sse" -> "sse";
                default -> "websocket";
            };
        }
        if (frontend == null || frontend.isBlank()) {
            System.out.println();
            System.out.println("Frontend:");
            System.out.println("  1) react     -- React + Vite frontend");
            System.out.println("  2) none      -- No frontend (API only)");
            var choice = prompt("Choose frontend [1-2]", "2");
            frontend = switch (choice) {
                case "1", "react" -> "react";
                default -> "none";
            };
        }
        if (aiFramework == null || aiFramework.isBlank()) {
            System.out.println();
            System.out.println("AI framework:");
            System.out.println("  1) builtin    -- OpenAI-compatible client (Gemini, Ollama, OpenAI)");
            System.out.println("  2) langchain4j -- LangChain4j StreamingChatLanguageModel");
            System.out.println("  3) spring-ai  -- Spring AI ChatClient");
            System.out.println("  4) adk        -- Google ADK Runner");
            var choice = prompt("Choose AI framework [1-4]", "1");
            aiFramework = switch (choice) {
                case "2", "langchain4j" -> "langchain4j";
                case "3", "spring-ai" -> "spring-ai";
                case "4", "adk" -> "adk";
                default -> "builtin";
            };
        }
        if (deploy == null || deploy.isBlank()) {
            System.out.println();
            System.out.println("Deployment mode:");
            System.out.println("  1) docker-compose -- Each agent runs in its own container");
            System.out.println("  2) single-jar     -- All agents bundled in one executable JAR");
            var choice = prompt("Choose deployment [1-2]", "1");
            deploy = switch (choice) {
                case "2", "single-jar" -> "single-jar";
                default -> "docker-compose";
            };
        }
    }

    void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("--name is required");
        }
        var validProtocols = List.of("a2a", "local");
        if (!validProtocols.contains(protocol)) {
            throw new IllegalArgumentException("--protocol must be one of: " + validProtocols);
        }
        var validTransports = List.of("websocket", "sse");
        if (!validTransports.contains(transport)) {
            throw new IllegalArgumentException("--transport must be one of: " + validTransports);
        }
        var validFrontends = List.of("react", "none");
        if (!validFrontends.contains(frontend)) {
            throw new IllegalArgumentException("--frontend must be one of: " + validFrontends);
        }
        var validAi = List.of("builtin", "langchain4j", "spring-ai", "adk");
        if (!validAi.contains(aiFramework)) {
            throw new IllegalArgumentException("--ai must be one of: " + validAi);
        }
        var validDeploy = List.of("docker-compose", "single-jar");
        if (!validDeploy.contains(deploy)) {
            throw new IllegalArgumentException("--deploy must be one of: " + validDeploy);
        }
    }

    // ── Model building ───────────────────────────────────────────────────────

    Map<String, Object> buildModel() {
        var m = new HashMap<String, Object>();
        m.put("name", name);
        m.put("projectName", name);
        m.put("groupId", groupId);
        m.put("artifactId", name);
        m.put("atmosphereVersion", readAtmosphereVersion());
        m.put("springBootVersion", readSpringBootVersion());

        // Protocol booleans
        m.put("isA2a", "a2a".equals(protocol));
        m.put("isLocal", "local".equals(protocol));

        // Transport booleans
        m.put("isWebsocket", "websocket".equals(transport));
        m.put("isSse", "sse".equals(transport));

        // Frontend booleans
        m.put("isReact", "react".equals(frontend));
        m.put("noFrontend", "none".equals(frontend));

        // AI framework booleans
        m.put("isBuiltin", "builtin".equals(aiFramework));
        m.put("isLangchain4j", "langchain4j".equals(aiFramework));
        m.put("isSpringAi", "spring-ai".equals(aiFramework));
        m.put("isAdk", "adk".equals(aiFramework));

        // Deployment booleans
        m.put("isDockerCompose", "docker-compose".equals(deploy));
        m.put("isSingleJar", "single-jar".equals(deploy));

        // Coordinator model
        m.put("coordinator", buildAgentModel(coordinator));

        // Agents list
        var agentModels = new ArrayList<Map<String, Object>>();
        for (var agent : agents) {
            agentModels.add(buildAgentModel(agent));
        }
        m.put("agents", agentModels);

        // Workflow steps
        m.put("workflowSteps", buildWorkflowModel());

        return m;
    }

    Map<String, Object> buildAgentModel(SkillDescriptor desc) {
        var m = new LinkedHashMap<String, Object>();
        m.put("projectName", name); // available in nested template contexts
        m.put("name", desc.name);
        m.put("description", desc.description);
        m.put("className", desc.className);
        m.put("packageName", groupId + "." + desc.packageSuffix);
        m.put("packagePath", (groupId + "." + desc.packageSuffix).replace('.', '/'));
        m.put("port", desc.port);
        m.put("envName", desc.name.replaceAll("[^a-zA-Z0-9]", "_").toUpperCase());
        m.put("varName", toCamelCase(desc.name));

        // Tools
        var toolMaps = new ArrayList<Map<String, Object>>();
        for (var tool : desc.tools) {
            var tm = new LinkedHashMap<String, Object>();
            tm.put("name", tool.name());
            tm.put("description", tool.description());
            tm.put("methodName", tool.methodName());
            toolMaps.add(tm);
        }
        m.put("tools", toolMaps);
        m.put("hasTools", !desc.tools.isEmpty());

        return m;
    }

    List<Map<String, Object>> buildWorkflowModel() {
        var steps = new ArrayList<Map<String, Object>>();
        var previousVars = new ArrayList<String>();

        for (int i = 0; i < workflowSteps.size(); i++) {
            var step = workflowSteps.get(i);
            var sm = new LinkedHashMap<String, Object>();
            sm.put("stepNumber", i + 1);
            sm.put("isParallel", step.isParallel());
            sm.put("isSequential", !step.isParallel());
            sm.put("isLast", i == workflowSteps.size() - 1);

            // Agent references in this step
            var stepAgents = new ArrayList<Map<String, String>>();
            for (var agentName : step.agentNames()) {
                var am = new LinkedHashMap<String, String>();
                am.put("name", agentName);
                am.put("varName", toCamelCase(agentName));
                stepAgents.add(am);
            }
            sm.put("agents", stepAgents);

            // Previous step variable names for building input context
            sm.put("previousVars", new ArrayList<>(previousVars));
            sm.put("hasPreviousVars", !previousVars.isEmpty());

            steps.add(sm);

            // Add this step's vars to previousVars for next iteration
            for (var agentName : step.agentNames()) {
                previousVars.add(toCamelCase(agentName) + "Result");
            }
        }

        return steps;
    }

    // ── Project generation ───────────────────────────────────────────────────

    void generateProject(Map<String, Object> model, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        // Parent POM
        renderTemplate("templates/compose/parent-pom.xml.mustache", model, outputDir.resolve("pom.xml"));

        // Coordinator module
        generateModule(model, coordinator, outputDir.resolve("coordinator"), true);

        // Agent modules
        for (var agent : agents) {
            generateModule(model, agent, outputDir.resolve("agents/" + sanitizeName(agent.name)), false);
        }

        // Docker Compose (if applicable)
        if ("docker-compose".equals(deploy)) {
            renderTemplate("templates/compose/docker-compose.yml.mustache", model, outputDir.resolve("docker-compose.yml"));
            renderTemplate("templates/compose/Dockerfile.mustache", model, outputDir.resolve("Dockerfile"));
        }

        // README
        renderTemplate("templates/compose/README.md.mustache", model, outputDir.resolve("README.md"));

        // Copy Maven Wrapper
        copyMavenWrapper(outputDir);
    }

    private void generateModule(Map<String, Object> model, SkillDescriptor desc,
                                 Path moduleDir, boolean isCoordinator) throws IOException {
        var agentModel = buildAgentModel(desc);
        var moduleModel = new HashMap<>(model);
        moduleModel.put("agent", agentModel);

        var pkgPath = (String) agentModel.get("packagePath");
        var javaDir = moduleDir.resolve("src/main/java").resolve(pkgPath);
        var resDir = moduleDir.resolve("src/main/resources");
        Files.createDirectories(javaDir);
        Files.createDirectories(resDir);

        if (isCoordinator) {
            renderTemplate("templates/compose/coordinator/pom.xml.mustache", moduleModel, moduleDir.resolve("pom.xml"));
            renderTemplate("templates/compose/Application.java.mustache", moduleModel, javaDir.resolve("Application.java"));
            renderTemplate("templates/compose/coordinator/Coordinator.java.mustache", moduleModel, javaDir.resolve(desc.className + ".java"));
            renderTemplate("templates/compose/coordinator/application.yml.mustache", moduleModel, resDir.resolve("application.yml"));
        } else {
            renderTemplate("templates/compose/agent/pom.xml.mustache", moduleModel, moduleDir.resolve("pom.xml"));
            renderTemplate("templates/compose/Application.java.mustache", moduleModel, javaDir.resolve("Application.java"));
            renderTemplate("templates/compose/agent/Agent.java.mustache", moduleModel, javaDir.resolve(desc.className + ".java"));
            renderTemplate("templates/compose/agent/application.yml.mustache", moduleModel, resDir.resolve("application.yml"));
        }

        // Copy skill file into resources
        if (desc.sourcePath != null && Files.isRegularFile(desc.sourcePath)) {
            var promptsDir = resDir.resolve("prompts");
            Files.createDirectories(promptsDir);
            Files.copy(desc.sourcePath, promptsDir.resolve("skill.md"), StandardCopyOption.REPLACE_EXISTING);

            // Copy resources/ directory if it exists alongside the skill file
            var skillResources = desc.sourcePath.getParent().resolve("resources");
            if (Files.isDirectory(skillResources)) {
                var targetResDir = resDir.resolve("skill-resources/" + sanitizeName(desc.name));
                copyDirectory(skillResources, targetResDir);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── Template rendering ───────────────────────────────────────────────────

    private void renderTemplate(String templatePath, Map<String, Object> model, Path target) throws IOException {
        // Check user override first
        var userOverride = Path.of(System.getProperty("user.home"), ".atmosphere", "templates",
                templatePath.replace("templates/", ""));
        Path templateFile;
        if (Files.isRegularFile(userOverride)) {
            templateFile = userOverride;
        } else {
            templateFile = scriptDir.resolve(templatePath);
        }

        var templateContent = Files.readString(templateFile);
        var compiler = Mustache.compiler().defaultValue("").withFormatter(
                (value) -> value == null ? "" : String.valueOf(value));
        var template = compiler.compile(new StringReader(templateContent));
        var rendered = template.execute(model);

        Files.createDirectories(target.getParent());
        Files.writeString(target, rendered);
    }

    // ── Utility methods ──────────────────────────────────────────────────────

    String extractYamlField(String frontmatter, String field) {
        var matcher = Pattern.compile("^" + Pattern.quote(field) + ":\\s*\"?([^\"\n]+)\"?",
                Pattern.MULTILINE).matcher(frontmatter);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    String extractSection(String content, String sectionName) {
        var pattern = Pattern.compile("## " + Pattern.quote(sectionName) + "\\s*\n(.*?)(?=\n## |\\z)",
                Pattern.DOTALL);
        var matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private void copyMavenWrapper(Path outputDir) throws IOException {
        // Look for mvnw in the repository root (parent of generator/)
        var repoRoot = scriptDir.getParent();
        var mvnw = repoRoot.resolve("mvnw");
        var mvnwCmd = repoRoot.resolve("mvnw.cmd");
        var mvnDir = repoRoot.resolve(".mvn");

        if (Files.isRegularFile(mvnw)) {
            Files.copy(mvnw, outputDir.resolve("mvnw"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(outputDir.resolve("mvnw"),
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ex) {
                // Windows -- POSIX permissions not supported
                System.getLogger(ComposeGenerator.class.getName())
                        .log(System.Logger.Level.TRACE, "POSIX file permissions not supported", ex);
            }
        }
        if (Files.isRegularFile(mvnwCmd)) {
            Files.copy(mvnwCmd, outputDir.resolve("mvnw.cmd"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.isDirectory(mvnDir)) {
            var targetMvnDir = outputDir.resolve(".mvn");
            Files.walkFileTree(mvnDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(targetMvnDir.resolve(mvnDir.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, targetMvnDir.resolve(mvnDir.relativize(file)),
                            StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    String readSpringBootVersion() {
        if (springBootVersionOverride != null && !springBootVersionOverride.isBlank()) {
            return springBootVersionOverride;
        }
        var repoRoot = scriptDir.getParent();
        var starterPom = repoRoot.resolve("modules/spring-boot-starter/pom.xml");
        if (Files.isRegularFile(starterPom)) {
            try {
                var content = Files.readString(starterPom);
                var matcher = Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>")
                        .matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (IOException ex) {
                System.getLogger(ComposeGenerator.class.getName())
                        .log(System.Logger.Level.TRACE, "Failed to read Spring Boot version from POM", ex);
            }
        }
        return "4.0.5";
    }

    String readAtmosphereVersion() {
        if (atmosphereVersionOverride != null && !atmosphereVersionOverride.isBlank()) {
            return atmosphereVersionOverride;
        }
        var repoRoot = scriptDir.getParent();
        var pomFile = repoRoot.resolve("pom.xml");
        if (Files.isRegularFile(pomFile)) {
            try {
                var content = Files.readString(pomFile);
                var matcher = Pattern.compile("<version>([^<]+)</version>").matcher(content);
                // Skip modelVersion, find project version
                while (matcher.find()) {
                    var ver = matcher.group(1);
                    if (ver.contains("SNAPSHOT") || ver.matches("\\d+\\.\\d+\\.\\d+.*")) {
                        return ver;
                    }
                }
            } catch (IOException ex) {
                System.getLogger(ComposeGenerator.class.getName())
                        .log(System.Logger.Level.TRACE, "Failed to read Atmosphere version from POM", ex);
            }
        }
        return "4.0.28";
    }

    private Path resolveScriptDir() {
        // JBang sets the source path when running a .java file directly
        var prop = System.getProperty("jbang.source");
        if (prop != null) {
            var source = Path.of(prop);
            var dir = Files.isDirectory(source) ? source : source.getParent();
            if (dir != null && Files.isDirectory(dir.resolve("templates"))) {
                return dir;
            }
        }
        // Walk up from CWD looking for the templates directory
        var cwd = Path.of("").toAbsolutePath();
        for (var dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("generator/templates"))) {
                return dir.resolve("generator");
            }
            if (Files.isDirectory(dir.resolve("templates")) && Files.exists(dir.resolve("templates/compose"))) {
                return dir;
            }
        }
        // Last resort with clear error
        throw new IllegalStateException(
                "Cannot find generator templates. Run from the Atmosphere repo root, "
                + "the generator/ directory, or set -Djbang.source=/path/to/generator/ComposeGenerator.java");
    }

    private static String prompt(String label, String defaultValue) {
        Console console = System.console();
        if (console != null) {
            var input = console.readLine("%s [%s]: ", label, defaultValue);
            return (input == null || input.isBlank()) ? defaultValue : input.trim();
        }
        // Fallback for non-console environments (IDE, piped input)
        System.out.printf("%s [%s]: ", label, defaultValue);
        System.out.flush();
        try {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            var input = reader.readLine();
            return (input == null || input.isBlank()) ? defaultValue : input.trim();
        } catch (IOException ex) {
            System.getLogger(ComposeGenerator.class.getName())
                    .log(System.Logger.Level.TRACE, "Failed to read user input", ex);
            return defaultValue;
        }
    }

    static String toPascalCase(String kebab) {
        var sb = new StringBuilder();
        var capitalize = true;
        for (char c : kebab.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String toCamelCase(String kebab) {
        var pascal = toPascalCase(kebab);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
