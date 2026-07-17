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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level CI gate that refuses to build if any sample {@code pom.xml}
 * hardcodes a LangChain4j version instead of the managed
 * {@code ${langchain4j.version}} (or another {@code ${...}} property such as
 * {@code ${quarkus-langchain4j.version}}).
 *
 * <h2>Why this gate exists</h2>
 * {@code atmosphere-langchain4j} declares LangChain4j as {@code provided} scope,
 * so each sample supplies its own {@code langchain4j-open-ai} coordinate. When
 * one sample pinned {@code 1.15.0} while the module built against the managed
 * {@code 1.17.0}, the older {@code ChatRequest.validate()} rejected a tool-using
 * request on the prompt-cache path with <em>"Cannot set both 'parameters' and
 * 'toolSpecifications' on ChatRequest"</em> — the spring-boot-orchestration-demo
 * agent surfaced an error frame instead of a reply. A hardcoded, drifting version
 * is invisible to a compile check but fatal at runtime; scanning the pom text is
 * the reliable way to keep every sample in lockstep with the BOM.
 *
 * <h2>Rule</h2>
 * Any {@code <dependency>} / {@code <dependencyManagement>} entry that references
 * a {@code dev.langchain4j} coordinate must express its {@code <version>} as a
 * {@code ${...}} property reference, or omit it entirely (BOM-managed). A literal
 * version (e.g. {@code 1.15.0}) fails the lint.
 */
class SampleLangChain4jVersionLintTest {

    /** A {@code <dependency>...</dependency>} block, captured whole. */
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    private static final Pattern VERSION =
            Pattern.compile("(?s)<version>\\s*(.*?)\\s*</version>");

    @Test
    void noSamplePomHardcodesALangChain4jVersion() throws IOException {
        var repoRoot = resolveRepoRoot();
        var samples = repoRoot.resolve("samples");
        assertTrue(Files.isDirectory(samples),
                "samples/ directory must exist at repo root: " + samples);

        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(samples)) {
            files.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(Files::isRegularFile)
                    // Skip generated/flattened poms so we only lint authored sources.
                    .filter(p -> !p.getFileName().toString().equals("dependency-reduced-pom.xml"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .forEach(p -> checkOne(repoRoot, p, offenders));
        }

        if (!offenders.isEmpty()) {
            var message = "The following sample pom.xml dependencies hardcode a LangChain4j "
                    + "version instead of the managed ${langchain4j.version} property. A pinned "
                    + "version drifts from the BOM the atmosphere-langchain4j module builds "
                    + "against and can crash tool-using agents at runtime (e.g. LangChain4j "
                    + "1.15.0's ChatRequest.validate rejects parameters + toolSpecifications). "
                    + "Use <version>${langchain4j.version}</version> (or omit it for BOM "
                    + "management):\n  "
                    + String.join("\n  ", offenders);
            throw new AssertionError(message);
        }
    }

    private static void checkOne(Path repoRoot, Path pom, List<String> offenders) {
        String text;
        try {
            text = Files.readString(pom);
        } catch (IOException e) {
            return;
        }
        Matcher blocks = DEPENDENCY_BLOCK.matcher(text);
        while (blocks.find()) {
            var block = blocks.group(1);
            if (!block.contains("dev.langchain4j")) {
                continue;
            }
            Matcher v = VERSION.matcher(block);
            if (!v.find()) {
                // No version → BOM-managed, which is fine.
                continue;
            }
            var version = v.group(1);
            if (!version.startsWith("${")) {
                offenders.add(repoRoot.relativize(pom) + " — langchain4j dependency pins "
                        + "literal version '" + version + "' (must be a ${...} property)");
            }
        }
    }

    private static Path resolveRepoRoot() {
        var cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var probe = cwd;
        while (probe != null && !Files.isDirectory(probe.resolve("samples"))) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new IllegalStateException("could not locate repo root from cwd=" + cwd);
        }
        return probe;
    }
}
