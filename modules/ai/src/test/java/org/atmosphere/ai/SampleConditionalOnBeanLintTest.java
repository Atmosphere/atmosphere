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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time lint closing the class of bug behind the 4.0.67 "rag-chat can never retrieve"
 * defect: {@code @ConditionalOnBean} / {@code @ConditionalOnMissingBean} used inside a sample's
 * plain {@code @Configuration}.
 *
 * <p>Those conditions are only contractually reliable inside auto-configuration classes. Spring
 * parses user {@code @Configuration} classes first and auto-configurations last (they arrive
 * through a {@code DeferredImportSelector}), so a user-config condition on a bean that an
 * auto-configuration contributes is evaluated against a registry that does not contain it yet —
 * it is silently, permanently false. `spring-boot-rag-chat` shipped exactly that: its
 * {@code VectorStore} was gated on {@code @ConditionalOnBean(EmbeddingModel.class)} and was
 * therefore never created, so every answer was ungrounded while still looking plausible. Nothing
 * failed; retrieval just quietly did not happen.</p>
 *
 * <p>Only {@code samples/} is walked. Framework modules legitimately use these annotations
 * inside {@code @AutoConfiguration} classes, which is the supported usage and is allowed here
 * too.</p>
 */
class SampleConditionalOnBeanLintTest {

    private static final Pattern OFFENDING_ANNOTATION =
            Pattern.compile("@ConditionalOn(Missing)?Bean\\b");
    private static final Pattern AUTO_CONFIGURATION =
            Pattern.compile("@AutoConfiguration\\b");

    /** Block comments, line comments and imports — stripped before matching. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//.*$");
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^\\s*import\\s+.*$");

    @Test
    void samplesDoNotUseConditionalOnBeanOutsideAutoConfiguration() throws IOException {
        var repoRoot = resolveRepoRoot();
        var samples = repoRoot.resolve("samples");
        assertTrue(Files.isDirectory(samples),
                "samples/ directory must exist at repo root: " + samples);

        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(samples)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .forEach(p -> checkOne(repoRoot, p, offenders));
        }

        assertTrue(offenders.isEmpty(),
                "@ConditionalOnBean/@ConditionalOnMissingBean is only reliable inside "
                        + "@AutoConfiguration classes. In a plain @Configuration it is evaluated "
                        + "before auto-configurations register their beans, so it is silently "
                        + "always false. Offenders:\n  " + String.join("\n  ", offenders));
    }

    /**
     * The lint must key off real annotation usage, not a mention in prose — otherwise a class
     * that merely documents the hazard (as {@code VectorStoreConfig} now does) reads as an
     * offender, and, worse, a genuine offender could be masked by stripping too much. This
     * pins the strip-then-match behaviour in both directions.
     */
    @Test
    void commentsAndImportsAreNotTreatedAsUsage() {
        var documentedOnly = """
                package demo;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
                /** Explains why @ConditionalOnBean is wrong here. */
                // @ConditionalOnBean(Foo.class)
                @Configuration
                class Demo { @Bean Foo foo() { return new Foo(); } }
                """;
        assertEquals(0, violationsIn(documentedOnly),
                "a Javadoc/line-comment/import mention must not count as usage");

        var realUsage = """
                package demo;
                @Configuration
                class Demo { @Bean @ConditionalOnBean(Foo.class) Foo foo() { return new Foo(); } }
                """;
        assertEquals(1, violationsIn(realUsage), "a real annotation must be flagged");

        var allowedInAutoConfig = """
                package demo;
                @AutoConfiguration
                class Demo { @Bean @ConditionalOnBean(Foo.class) Foo foo() { return new Foo(); } }
                """;
        assertEquals(0, violationsIn(allowedInAutoConfig),
                "@AutoConfiguration is the supported home for these conditions");
    }

    private static int violationsIn(String source) {
        var stripped = strip(source);
        if (AUTO_CONFIGURATION.matcher(stripped).find()) {
            return 0;
        }
        return OFFENDING_ANNOTATION.matcher(stripped).find() ? 1 : 0;
    }

    private static String strip(String source) {
        var s = BLOCK_COMMENT.matcher(source).replaceAll("");
        s = LINE_COMMENT.matcher(s).replaceAll("");
        return IMPORT_LINE.matcher(s).replaceAll("");
    }

    private static void checkOne(Path repoRoot, Path javaFile, List<String> offenders) {
        String source;
        try {
            source = Files.readString(javaFile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + javaFile, e);
        }
        if (violationsIn(source) > 0) {
            offenders.add(repoRoot.relativize(javaFile)
                    + " — uses @ConditionalOnBean/@ConditionalOnMissingBean in a class that is "
                    + "not annotated @AutoConfiguration");
        }
    }

    private static Path resolveRepoRoot() {
        var probe = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (probe != null && !Files.isDirectory(probe.resolve("samples"))) {
            probe = probe.getParent();
        }
        assertTrue(probe != null, "could not locate the repo root (no samples/ ancestor)");
        return probe;
    }
}
