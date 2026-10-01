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
package org.atmosphere.ai.annotation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every {@code AgentScope#member} reference in the module's main sources
 * to a member {@link AgentScope} actually declares. The
 * {@code SemanticIntentScopeGuardrail} Javadoc once told operators to set the
 * margin through {@code AgentScope#semanticIntentMargin()}, a member that never
 * existed; the Javadoc build did not reject it, so this scan does.
 */
class AgentScopeReferencesTest {

    private static final Pattern MEMBER_REFERENCE = Pattern.compile("\\bAgentScope#(\\w+)");

    @Test
    void everyAgentScopeMemberReferenceNamesADeclaredMember() throws IOException {
        var declared = Arrays.stream(AgentScope.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        var root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "run from the module directory: " + root.toAbsolutePath());

        var phantom = new ArrayList<String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (var member : referencedMembers(Files.readString(file))) {
                    if (!declared.contains(member)) {
                        phantom.add(root.relativize(file) + " -> AgentScope#" + member);
                    }
                }
            }
        }
        assertTrue(phantom.isEmpty(),
                "references to AgentScope members that do not exist: " + phantom);
    }

    @Test
    void theScanFindsAFullyQualifiedJavadocLink() {
        // The exact shape of the phantom reference the scan exists to catch.
        assertEquals(List.of("semanticIntentMargin"), referencedMembers(
                " * {@link org.atmosphere.ai.annotation.AgentScope#semanticIntentMargin()}"));
        assertEquals(List.of("purpose", "similarityThreshold"), referencedMembers(
                "{@link AgentScope#purpose()} and {@link AgentScope#similarityThreshold()}"));
    }

    private static List<String> referencedMembers(String source) {
        var matcher = MEMBER_REFERENCE.matcher(source);
        var members = new ArrayList<String>();
        while (matcher.find()) {
            members.add(matcher.group(1));
        }
        return members;
    }
}
