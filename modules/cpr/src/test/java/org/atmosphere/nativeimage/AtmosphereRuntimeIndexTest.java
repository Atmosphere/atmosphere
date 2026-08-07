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
package org.atmosphere.nativeimage;

import org.atmosphere.cpr.AtmosphereReflectiveTypes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps this module's own annotation index in step with the framework's list.
 *
 * <p>Every other artifact gets its index written by
 * {@link AtmosphereAnnotationIndexProcessor} during compilation. This module
 * cannot: the processor is defined here, so {@code javac} has nothing to load
 * while compiling it, and the pom sets {@code proc:none} accordingly.</p>
 *
 * <p>The index still has to exist. In a native image the classpath scan finds
 * nothing, so the framework's own annotation processors — the classes that make
 * {@code @ManagedService} and friends work at all — are discoverable only
 * through this file. It is committed, and this test regenerates and compares so
 * it cannot quietly fall behind.</p>
 */
class AtmosphereRuntimeIndexTest {

    private static final Path INDEX =
            Path.of("src/main/resources/META-INF/atmosphere/annotated-classes.txt");

    private static final String HEADER = """
            # Atmosphere-annotated classes in this artifact.
            #
            # Written by this module's gate test rather than by the annotation processor:
            # the processor is defined here, so javac cannot run it against this module
            # (see proc:none in pom.xml). Regenerate with AtmosphereRuntimeIndexTest.
            """;

    /** The framework's own processors, which a native image cannot scan for. */
    private static String expected() {
        var body = new StringBuilder(HEADER);
        AtmosphereReflectiveTypes.annotationProcessors().stream()
                .sorted()
                .forEach(name -> body.append(name).append('\n'));
        return body.toString();
    }

    @Test
    void theIndexIsShipped() {
        assertTrue(Files.isRegularFile(INDEX),
                "expected " + INDEX.toAbsolutePath() + " — without it a native image cannot "
                        + "discover the framework's own annotation processors, and every "
                        + "@ManagedService endpoint stops working");
    }

    @Test
    void theCommittedIndexMatchesTheFrameworksList() throws Exception {
        var expected = expected();
        var actual = Files.readString(INDEX, StandardCharsets.UTF_8);

        if (!expected.equals(actual)) {
            Files.writeString(INDEX.resolveSibling("annotated-classes.txt.expected"),
                    expected, StandardCharsets.UTF_8);
        }

        assertEquals(expected, actual,
                "this module's annotation index drifted from "
                        + "AtmosphereReflectiveTypes.annotationProcessors(). Regenerated copy "
                        + "written alongside as annotated-classes.txt.expected — replace the "
                        + "committed file with it.");
    }

    @Test
    void theIndexNamesRealClasses() throws Exception {
        var names = Files.readAllLines(INDEX).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .toList();

        assertTrue(names.size() >= 10,
                "the framework ships many annotation processors; a near-empty index means "
                        + "the generator is broken: " + names);

        var unresolvable = new java.util.ArrayList<String>();
        for (var name : names) {
            try {
                Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                unresolvable.add(name);
            }
        }
        assertEquals(List.of(), unresolvable,
                "every name here must resolve — an entry that does not is dead weight in "
                        + "the index and a sign the source list has gone stale");
    }
}
