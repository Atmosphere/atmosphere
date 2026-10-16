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
package org.atmosphere.samples.springboot.teamrooms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README must tell a reader how the Console really picks its room. The Console has no
 * URL parameter or UI field for its endpoint: the room it joins is the one the boot
 * property {@code atmosphere.console-endpoint} names. The 2026-10-16 sweep found the README
 * telling readers to "point the Console at" a room path, which nothing in the Console does.
 */
class ReadmeConsoleRoomTest {

    private static final Pattern CONSOLE_ENDPOINT =
            Pattern.compile("(?m)^\\s*console-endpoint:\\s*(\\S+)\\s*$");

    private static String read(String path) throws IOException {
        var file = Path.of(path);
        assertTrue(Files.isReadable(file), path + " must be readable from the module directory");
        return Files.readString(file);
    }

    @Test
    void readmeNamesTheBootPropertyThatChoosesTheConsoleRoom() throws IOException {
        var readme = read("README.md");
        var yml = read("src/main/resources/application.yml");

        var matcher = CONSOLE_ENDPOINT.matcher(yml);
        assertTrue(matcher.find(), "application.yml no longer sets atmosphere.console-endpoint");
        var configuredRoom = matcher.group(1);

        assertFalse(readme.contains("pointing the Console at"),
                "README still tells readers to point the Console at a room path; the Console has "
                        + "no URL or UI way to do that");
        assertTrue(readme.contains("atmosphere.console-endpoint"),
                "README must name atmosphere.console-endpoint, the property that picks the Console's room");
        assertTrue(readme.contains(configuredRoom),
                "README must name the room application.yml actually joins: " + configuredRoom);
    }
}
