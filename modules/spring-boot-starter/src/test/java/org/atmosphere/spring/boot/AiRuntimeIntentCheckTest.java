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
package org.atmosphere.spring.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code atmosphere.ai.runtime} selects nothing — the highest-priority runtime
 * module on the classpath runs. A sample set it to {@code spring-ai} with no
 * Spring AI adapter on its classpath and silently ran the built-in runtime.
 */
class AiRuntimeIntentCheckTest {

    @Test
    void aConfiguredRuntimeThatIsNotRunningIsReported() {
        var warning = AtmosphereAiAutoConfiguration.runtimeIntentWarning("spring-ai", "built-in");
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("atmosphere.ai.runtime=spring-ai is not honored"),
                warning.get());
        assertTrue(warning.get().contains("'built-in'"), warning.get());
    }

    @Test
    void nothingIsReportedWhenUnsetOrMatching() {
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning(null, "built-in").isEmpty());
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning(" ", "built-in").isEmpty());
        assertTrue(AtmosphereAiAutoConfiguration.runtimeIntentWarning("Spring-AI ", "spring-ai").isEmpty());
    }

    /**
     * Gate: no sample configures the key, since it would only produce the
     * warning above — the samples document switching as a dependency swap.
     */
    @Test
    void noSampleConfiguresTheRuntimeKey() throws IOException {
        var samples = Path.of("../../samples").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(samples), "samples directory: " + samples);
        var offenders = new ArrayList<String>();
        var scanned = 0;
        try (var files = Files.walk(samples)) {
            for (var file : files.filter(AiRuntimeIntentCheckTest::isApplicationConfig).toList()) {
                scanned++;
                var content = Files.readString(file);
                var keys = file.toString().endsWith(".properties")
                        ? propertyKeys(content) : yamlKeys(content);
                if (keys.contains("atmosphere.ai.runtime")) {
                    offenders.add(samples.relativize(file).toString());
                }
            }
        }
        assertTrue(scanned > 10, "expected to scan the samples' application configs, got " + scanned);
        assertEquals(java.util.List.of(), offenders);
    }

    private static boolean isApplicationConfig(Path file) {
        var name = file.getFileName().toString();
        return file.toString().contains("/src/main/resources/") && !file.toString().contains("/target/")
                && name.startsWith("application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    private static java.util.Set<String> propertyKeys(String content) {
        var keys = new java.util.HashSet<String>();
        for (var line : content.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            var eq = trimmed.indexOf('=');
            keys.add((eq < 0 ? trimmed : trimmed.substring(0, eq)).trim());
        }
        return keys;
    }

    /** Dotted paths of the mapping keys in a block-style YAML file. */
    private static java.util.Set<String> yamlKeys(String content) {
        var keyLine = Pattern.compile("^( *)([A-Za-z0-9_.-]+):(.*)$");
        var keys = new java.util.HashSet<String>();
        var path = new ArrayDeque<String[]>();   // {indent, key}
        for (var line : content.split("\n")) {
            if (line.trim().startsWith("#") || line.trim().startsWith("-") || line.trim().equals("---")) {
                continue;
            }
            var m = keyLine.matcher(line);
            if (!m.matches()) {
                continue;
            }
            var indent = m.group(1).length();
            while (!path.isEmpty() && Integer.parseInt(path.peek()[0]) >= indent) {
                path.pop();
            }
            path.push(new String[] {String.valueOf(indent), m.group(2)});
            var parts = new ArrayList<String>();
            path.descendingIterator().forEachRemaining(p -> parts.add(p[1]));
            keys.add(String.join(".", parts));
        }
        return keys;
    }
}
