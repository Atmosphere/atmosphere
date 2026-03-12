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
//SOURCES AtmosphereInit.java
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

import java.io.PrintWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Unit tests for AtmosphereInit generator.
 *
 * Run with: jbang generator/AtmosphereInitTest.java
 */
public class AtmosphereInitTest {

    public static void main(String... args) {
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(AtmosphereInitTest.class))
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

    // ---- Helper to build a configured generator instance ----

    private AtmosphereInit generator(String name, String handler, String aiFramework) {
        return generator(name, handler, aiFramework, false);
    }

    private AtmosphereInit generator(String name, String handler, String aiFramework, boolean tools) {
        var gen = new AtmosphereInit();
        gen.name = name;
        gen.handler = handler;
        gen.aiFramework = aiFramework;
        gen.tools = tools;
        gen.groupId = "com.example";
        gen.scriptDir = Path.of("generator").toAbsolutePath();
        return gen;
    }

    // ========== buildModel() — handler flags ==========

    @Test
    void buildModel_chat_setsIsChatTrue() {
        var model = generator("myapp", "chat", null).buildModel();
        assertEquals(true, model.get("isChat"));
        assertEquals(false, model.get("isAiChat"));
        assertEquals(false, model.get("isMcpServer"));
    }

    @Test
    void buildModel_aiChat_setsIsAiChatTrue() {
        var model = generator("myapp", "ai-chat", "builtin").buildModel();
        assertEquals(false, model.get("isChat"));
        assertEquals(true, model.get("isAiChat"));
        assertEquals(false, model.get("isMcpServer"));
    }

    @Test
    void buildModel_mcpServer_setsIsMcpServerTrue() {
        var model = generator("myapp", "mcp-server", null).buildModel();
        assertEquals(false, model.get("isChat"));
        assertEquals(false, model.get("isAiChat"));
        assertEquals(true, model.get("isMcpServer"));
    }

    // ========== buildModel() — AI framework flags ==========

    @Test
    void buildModel_builtin_setsIsBuiltinTrue() {
        var model = generator("myapp", "ai-chat", "builtin").buildModel();
        assertEquals(true, model.get("isBuiltin"));
        assertEquals(false, model.get("isSpringAi"));
        assertEquals(false, model.get("isLangchain4j"));
        assertEquals(false, model.get("isAdk"));
        assertEquals(false, model.get("isEmbabel"));
    }

    @Test
    void buildModel_springAi_setsIsSpringAiTrue() {
        var model = generator("myapp", "ai-chat", "spring-ai").buildModel();
        assertEquals(false, model.get("isBuiltin"));
        assertEquals(true, model.get("isSpringAi"));
        assertEquals(false, model.get("isLangchain4j"));
        assertEquals(false, model.get("isAdk"));
        assertEquals(false, model.get("isEmbabel"));
    }

    @Test
    void buildModel_langchain4j_setsIsLangchain4jTrue() {
        var model = generator("myapp", "ai-chat", "langchain4j").buildModel();
        assertEquals(false, model.get("isBuiltin"));
        assertEquals(false, model.get("isSpringAi"));
        assertEquals(true, model.get("isLangchain4j"));
        assertEquals(false, model.get("isAdk"));
        assertEquals(false, model.get("isEmbabel"));
    }

    @Test
    void buildModel_adk_setsIsAdkTrue() {
        var model = generator("myapp", "ai-chat", "adk").buildModel();
        assertEquals(false, model.get("isBuiltin"));
        assertEquals(false, model.get("isSpringAi"));
        assertEquals(false, model.get("isLangchain4j"));
        assertEquals(true, model.get("isAdk"));
        assertEquals(false, model.get("isEmbabel"));
    }

    @Test
    void buildModel_embabel_setsIsEmbabelTrue() {
        var model = generator("myapp", "ai-chat", "embabel").buildModel();
        assertEquals(false, model.get("isBuiltin"));
        assertEquals(false, model.get("isSpringAi"));
        assertEquals(false, model.get("isLangchain4j"));
        assertEquals(false, model.get("isAdk"));
        assertEquals(true, model.get("isEmbabel"));
    }

    // ========== buildModel() — derived config flags ==========

    @Test
    void buildModel_builtin_usesLlmConfig() {
        var model = generator("myapp", "ai-chat", "builtin").buildModel();
        assertEquals(true, model.get("usesLlmConfig"));
        assertEquals(false, model.get("usesSpringAiConfig"));
        assertEquals(false, model.get("usesAdkConfig"));
    }

    @Test
    void buildModel_langchain4j_usesLlmConfig() {
        var model = generator("myapp", "ai-chat", "langchain4j").buildModel();
        assertEquals(true, model.get("usesLlmConfig"));
        assertEquals(false, model.get("usesSpringAiConfig"));
        assertEquals(false, model.get("usesAdkConfig"));
    }

    @Test
    void buildModel_springAi_usesSpringAiConfig() {
        var model = generator("myapp", "ai-chat", "spring-ai").buildModel();
        assertEquals(false, model.get("usesLlmConfig"));
        assertEquals(true, model.get("usesSpringAiConfig"));
        assertEquals(false, model.get("usesAdkConfig"));
    }

    @Test
    void buildModel_embabel_usesSpringAiConfig() {
        var model = generator("myapp", "ai-chat", "embabel").buildModel();
        assertEquals(false, model.get("usesLlmConfig"));
        assertEquals(true, model.get("usesSpringAiConfig"));
        assertEquals(false, model.get("usesAdkConfig"));
    }

    @Test
    void buildModel_adk_usesAdkConfig() {
        var model = generator("myapp", "ai-chat", "adk").buildModel();
        assertEquals(false, model.get("usesLlmConfig"));
        assertEquals(false, model.get("usesSpringAiConfig"));
        assertEquals(true, model.get("usesAdkConfig"));
    }

    // ========== buildModel() — producer flags ==========

    @Test
    void buildModel_aiChat_builtin_needsDemoProducer() {
        var model = generator("myapp", "ai-chat", "builtin").buildModel();
        assertEquals(true, model.get("needsDemoProducer"));
        assertEquals(false, model.get("needsAdkProducer"));
    }

    @Test
    void buildModel_aiChat_springAi_needsDemoProducer() {
        var model = generator("myapp", "ai-chat", "spring-ai").buildModel();
        assertEquals(true, model.get("needsDemoProducer"));
        assertEquals(false, model.get("needsAdkProducer"));
    }

    @Test
    void buildModel_aiChat_adk_needsAdkProducer() {
        var model = generator("myapp", "ai-chat", "adk").buildModel();
        assertEquals(false, model.get("needsDemoProducer"));
        assertEquals(true, model.get("needsAdkProducer"));
    }

    @Test
    void buildModel_chat_noProducerNeeded() {
        var model = generator("myapp", "chat", null).buildModel();
        assertEquals(false, model.get("needsDemoProducer"));
        assertEquals(false, model.get("needsAdkProducer"));
    }

    // ========== buildModel() — name derivation ==========

    @Test
    void buildModel_packageName_stripsHyphens() {
        var model = generator("my-cool-app", "chat", null).buildModel();
        assertEquals("com.example.mycoolapp", model.get("packageName"));
    }

    @Test
    void buildModel_packagePath_convertsDotsToSlashes() {
        var model = generator("my-cool-app", "chat", null).buildModel();
        assertEquals("com/example/mycoolapp", model.get("packagePath"));
    }

    @Test
    void buildModel_artifactId_matchesName() {
        var model = generator("my-cool-app", "chat", null).buildModel();
        assertEquals("my-cool-app", model.get("artifactId"));
    }

    // ========== validate() — rejection ==========

    @Test
    void validate_blankName_throws() {
        var gen = generator("", "chat", null);
        gen.name = "";
        assertThrows(IllegalArgumentException.class, gen::validate);
    }

    @Test
    void validate_nullName_throws() {
        var gen = generator("myapp", "chat", null);
        gen.name = null;
        assertThrows(IllegalArgumentException.class, gen::validate);
    }

    @Test
    void validate_badHandler_throws() {
        var gen = generator("myapp", "invalid-handler", null);
        assertThrows(IllegalArgumentException.class, gen::validate);
    }

    @Test
    void validate_aiChat_nullFramework_throws() {
        var gen = generator("myapp", "ai-chat", null);
        assertThrows(IllegalArgumentException.class, gen::validate);
    }

    @Test
    void validate_aiChat_badFramework_throws() {
        var gen = generator("myapp", "ai-chat", "invalid-framework");
        assertThrows(IllegalArgumentException.class, gen::validate);
    }

    // ========== validate() — acceptance ==========

    @Test
    void validate_chat_passes() {
        assertDoesNotThrow(() -> generator("myapp", "chat", null).validate());
    }

    @Test
    void validate_aiChat_builtin_passes() {
        assertDoesNotThrow(() -> generator("myapp", "ai-chat", "builtin").validate());
    }

    @Test
    void validate_mcpServer_passes() {
        assertDoesNotThrow(() -> generator("myapp", "mcp-server", null).validate());
    }

    @Test
    void validate_aiChat_allFrameworks_pass() {
        for (var fw : new String[]{"builtin", "spring-ai", "langchain4j", "adk", "embabel"}) {
            assertDoesNotThrow(() -> generator("myapp", "ai-chat", fw).validate(),
                    "Expected ai-chat/" + fw + " to pass validation");
        }
    }

    // ========== buildModel() — tools flag ==========

    @Test
    void buildModel_aiChat_withTools_setsHasToolsTrue() {
        var model = generator("myapp", "ai-chat", "builtin", true).buildModel();
        assertEquals(true, model.get("hasTools"));
    }

    @Test
    void buildModel_aiChat_withoutTools_setsHasToolsFalse() {
        var model = generator("myapp", "ai-chat", "builtin", false).buildModel();
        assertEquals(false, model.get("hasTools"));
    }

    @Test
    void buildModel_chat_withTools_setsHasToolsFalse() {
        var model = generator("myapp", "chat", null, true).buildModel();
        assertEquals(false, model.get("hasTools"));
    }

    // ========== readAtmosphereVersion() ==========

    @Test
    void readAtmosphereVersion_readsRealPom() {
        var gen = generator("myapp", "chat", null);
        // scriptDir points to generator/, parent is repo root with pom.xml
        var version = gen.readAtmosphereVersion();
        assertNotNull(version);
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"),
                "Expected version like x.y.z but got: " + version);
    }

    @Test
    void readAtmosphereVersion_fallsBackWhenMissing(@TempDir Path tempDir) {
        var gen = generator("myapp", "chat", null);
        // Point scriptDir to a temp dir (no pom.xml in parent)
        gen.scriptDir = tempDir.resolve("generator");
        var version = gen.readAtmosphereVersion();
        assertEquals("4.0.11", version);
    }
}
