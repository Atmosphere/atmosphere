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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Command(name = "atmosphere-init",
        mixinStandardHelpOptions = true,
        version = "atmosphere-init 1.0",
        description = "Generate an Atmosphere project")
public class AtmosphereInit implements Runnable {

    @Option(names = {"-n", "--name"}, description = "Project name (e.g. my-chat-app)")
    String name;

    @Option(names = {"-g", "--group"}, defaultValue = "com.example", description = "Group ID")
    String groupId;

    @Option(names = {"--handler"}, description = "Handler type: chat, ai-chat, mcp-server")
    String handler;

    @Option(names = {"--ai"}, description = "AI framework: builtin, spring-ai, langchain4j, adk, embabel")
    String aiFramework;

    @Option(names = {"--tools"}, description = "Include example @AiTool methods (ai-chat only)")
    Boolean tools;

    @Option(names = {"-o", "--output"}, description = "Output directory")
    Path output;

    Path scriptDir;

    public static void main(String... args) {
        int exitCode = new CommandLine(new AtmosphereInit()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        scriptDir = resolveScriptDir();

        promptForMissing();
        validate();

        var model = buildModel();
        var outputDir = output != null ? output : Path.of(name);

        try {
            generateProject(model, outputDir);
            System.out.println();
            System.out.println("Project generated in: " + outputDir.toAbsolutePath());
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  cd " + outputDir);
            System.out.println("  ./mvnw spring-boot:run");
            System.out.println("  open http://localhost:" + model.get("serverPort"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate project", e);
        }
    }

    private void promptForMissing() {
        if (name == null || name.isBlank()) {
            name = prompt("Project name", "my-atmosphere-app");
        }
        if (handler == null || handler.isBlank()) {
            System.out.println();
            System.out.println("Handler types:");
            System.out.println("  1) chat       — real-time chat with @ManagedService");
            System.out.println("  2) ai-chat    — AI streaming with @AiEndpoint");
            System.out.println("  3) mcp-server — MCP tools + chat with @McpServer");
            var choice = prompt("Choose handler [1-3]", "1");
            handler = switch (choice) {
                case "2", "ai-chat" -> "ai-chat";
                case "3", "mcp-server" -> "mcp-server";
                default -> "chat";
            };
        }
        if ("ai-chat".equals(handler) && (aiFramework == null || aiFramework.isBlank())) {
            System.out.println();
            System.out.println("AI frameworks:");
            System.out.println("  1) builtin    — OpenAI-compatible client (Gemini, Ollama, OpenAI)");
            System.out.println("  2) spring-ai  — Spring AI ChatClient");
            System.out.println("  3) langchain4j — LangChain4j StreamingChatLanguageModel");
            System.out.println("  4) adk        — Google ADK Runner");
            System.out.println("  5) embabel    — Embabel AgentPlatform");
            var choice = prompt("Choose AI framework [1-5]", "1");
            aiFramework = switch (choice) {
                case "2", "spring-ai" -> "spring-ai";
                case "3", "langchain4j" -> "langchain4j";
                case "4", "adk" -> "adk";
                case "5", "embabel" -> "embabel";
                default -> "builtin";
            };
        }
        if ("ai-chat".equals(handler) && tools == null) {
            var choice = prompt("Include example @AiTool methods? [y/N]", "n");
            tools = "y".equalsIgnoreCase(choice) || "yes".equalsIgnoreCase(choice);
        }
    }

    void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("--name is required");
        }
        var validHandlers = List.of("chat", "ai-chat", "mcp-server");
        if (!validHandlers.contains(handler)) {
            throw new IllegalArgumentException("--handler must be one of: " + validHandlers);
        }
        if ("ai-chat".equals(handler)) {
            var validAi = List.of("builtin", "spring-ai", "langchain4j", "adk", "embabel");
            if (aiFramework == null || !validAi.contains(aiFramework)) {
                throw new IllegalArgumentException("--ai must be one of: " + validAi);
            }
        }
    }

    Map<String, Object> buildModel() {
        var m = new HashMap<String, Object>();
        m.put("name", name);
        m.put("groupId", groupId);
        m.put("artifactId", name);
        m.put("packageName", groupId + "." + name.replaceAll("[^a-zA-Z0-9]", ""));
        m.put("packagePath", (groupId + "." + name.replaceAll("[^a-zA-Z0-9]", "")).replace('.', '/'));
        m.put("atmosphereVersion", readAtmosphereVersion());
        m.put("springBootVersion", "4.0.2");
        m.put("serverPort", "8080");

        // Handler booleans
        m.put("isChat", "chat".equals(handler));
        m.put("isAiChat", "ai-chat".equals(handler));
        m.put("isMcpServer", "mcp-server".equals(handler));

        // AI framework booleans
        boolean isBuiltin = "builtin".equals(aiFramework);
        boolean isSpringAi = "spring-ai".equals(aiFramework);
        boolean isLangchain4j = "langchain4j".equals(aiFramework);
        boolean isAdk = "adk".equals(aiFramework);
        boolean isEmbabel = "embabel".equals(aiFramework);

        m.put("isBuiltin", isBuiltin);
        m.put("isSpringAi", isSpringAi);
        m.put("isLangchain4j", isLangchain4j);
        m.put("isAdk", isAdk);
        m.put("isEmbabel", isEmbabel);

        // Derived config style
        m.put("usesLlmConfig", isBuiltin || isLangchain4j);
        m.put("usesSpringAiConfig", isSpringAi || isEmbabel);
        m.put("usesAdkConfig", isAdk);
        m.put("needsDemoProducer", "ai-chat".equals(handler) && !isAdk);
        m.put("needsAdkProducer", isAdk);

        // Tool support
        boolean hasTools = Boolean.TRUE.equals(tools) && "ai-chat".equals(handler);
        m.put("hasTools", hasTools);

        return m;
    }

    private void generateProject(Map<String, Object> model, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        var pkgPath = (String) model.get("packagePath");
        var javaDir = outputDir.resolve("src/main/java").resolve(pkgPath);
        var resDir = outputDir.resolve("src/main/resources");
        var staticDir = resDir.resolve("static");
        Files.createDirectories(javaDir);
        Files.createDirectories(resDir);
        Files.createDirectories(staticDir);

        // Render POM
        renderTemplate("templates/pom.xml.mustache", model, outputDir.resolve("pom.xml"));

        // Render Application.java
        renderTemplate("templates/Application.java.mustache", model, javaDir.resolve("Application.java"));

        // Render application.yml
        renderTemplate("templates/application.yml.mustache", model, resDir.resolve("application.yml"));

        // Render handler-specific files
        var handlerType = handler;
        switch (handlerType) {
            case "chat" -> {
                renderTemplate("templates/handler/chat/Chat.java.mustache", model, javaDir.resolve("Chat.java"));
                renderTemplate("templates/handler/chat/Message.java.mustache", model, javaDir.resolve("Message.java"));
                renderTemplate("templates/handler/chat/JacksonEncoder.java.mustache", model, javaDir.resolve("JacksonEncoder.java"));
                renderTemplate("templates/handler/chat/JacksonDecoder.java.mustache", model, javaDir.resolve("JacksonDecoder.java"));
                copyFrontend("chat", staticDir);
            }
            case "ai-chat" -> {
                renderTemplate("templates/handler/ai-chat/AiChat.java.mustache", model, javaDir.resolve("AiChat.java"));
                if ((boolean) model.get("hasTools")) {
                    renderTemplate("templates/handler/ai-chat/AssistantTools.java.mustache", model, javaDir.resolve("AssistantTools.java"));
                }
                if ((boolean) model.get("needsDemoProducer")) {
                    renderTemplate("templates/handler/ai-chat/DemoResponseProducer.java.mustache", model, javaDir.resolve("DemoResponseProducer.java"));
                }
                if ((boolean) model.get("needsAdkProducer")) {
                    renderTemplate("templates/handler/ai-chat/DemoEventProducer.java.mustache", model, javaDir.resolve("DemoEventProducer.java"));
                }
                if ((boolean) model.get("usesLlmConfig")) {
                    renderTemplate("templates/handler/ai-chat/LlmConfig.java.mustache", model, javaDir.resolve("LlmConfig.java"));
                }
                // System prompt
                var promptsDir = resDir.resolve("prompts");
                Files.createDirectories(promptsDir);
                var promptFile = (boolean) model.get("hasTools")
                        ? "templates/handler/ai-chat/system-prompt-tools.md"
                        : "templates/handler/ai-chat/system-prompt.md";
                Files.copy(scriptDir.resolve(promptFile),
                        promptsDir.resolve("system-prompt.md"), StandardCopyOption.REPLACE_EXISTING);
                copyFrontend("ai-chat", staticDir);
            }
            case "mcp-server" -> {
                renderTemplate("templates/handler/mcp-server/Chat.java.mustache", model, javaDir.resolve("Chat.java"));
                renderTemplate("templates/handler/mcp-server/DemoMcpServer.java.mustache", model, javaDir.resolve("DemoMcpServer.java"));
                renderTemplate("templates/handler/mcp-server/Message.java.mustache", model, javaDir.resolve("Message.java"));
                renderTemplate("templates/handler/mcp-server/JacksonEncoder.java.mustache", model, javaDir.resolve("JacksonEncoder.java"));
                renderTemplate("templates/handler/mcp-server/JacksonDecoder.java.mustache", model, javaDir.resolve("JacksonDecoder.java"));
                copyFrontend("mcp-server", staticDir);
            }
        }

        // Copy Maven Wrapper
        copyMavenWrapper(outputDir);
    }

    private void renderTemplate(String templatePath, Map<String, Object> model, Path target) throws IOException {
        var templateFile = scriptDir.resolve(templatePath);
        var templateContent = Files.readString(templateFile);
        var compiler = Mustache.compiler().defaultValue("");
        var template = compiler.compile(new StringReader(templateContent));
        var rendered = template.execute(model);
        Files.createDirectories(target.getParent());
        Files.writeString(target, rendered);
    }

    private void copyFrontend(String handlerType, Path staticDir) throws IOException {
        var srcDir = scriptDir.resolve("templates/frontend/" + handlerType);
        if (!Files.isDirectory(srcDir)) {
            return;
        }
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                var relative = srcDir.relativize(dir);
                Files.createDirectories(staticDir.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var relative = srcDir.relativize(file);
                Files.copy(file, staticDir.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
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
            } catch (UnsupportedOperationException ignored) {
                // Windows
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

    String readAtmosphereVersion() {
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
            } catch (IOException ignored) {
            }
        }
        return "4.0.15";
    }

    private Path resolveScriptDir() {
        // JBang sets the source path; we can also detect from class location
        var prop = System.getProperty("jbang.source");
        if (prop != null) {
            return Path.of(prop).getParent();
        }
        // Fallback: check if generator/ dir exists relative to CWD
        var cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("generator/templates"))) {
            return cwd.resolve("generator");
        }
        if (Files.isDirectory(cwd.resolve("templates"))) {
            return cwd;
        }
        // Last resort: assume we're in the generator dir
        return cwd;
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
        } catch (IOException e) {
            return defaultValue;
        }
    }
}
