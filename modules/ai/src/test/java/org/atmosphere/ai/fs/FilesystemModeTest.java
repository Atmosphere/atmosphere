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
package org.atmosphere.ai.fs;

import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@link FilesystemMode} tri-state knob: lenient parse (unrecognized
 * collapses to AUTO — Correctness Invariant #4) and the
 * {@code atmosphere.ai.filesystem} sysprop resolution through
 * {@link AiConfig#resolveFilesystemMode()}.
 */
public class FilesystemModeTest {

    @AfterEach
    public void clearProperty() {
        System.clearProperty(AiConfig.FILESYSTEM_PROPERTY);
    }

    @Test
    public void parseIsLenient() {
        assertEquals(FilesystemMode.AUTO, FilesystemMode.parse(null));
        assertEquals(FilesystemMode.AUTO, FilesystemMode.parse(""));
        assertEquals(FilesystemMode.AUTO, FilesystemMode.parse("  "));
        assertEquals(FilesystemMode.AUTO, FilesystemMode.parse("auto"));
        assertEquals(FilesystemMode.AUTO, FilesystemMode.parse("garbage"));
        assertEquals(FilesystemMode.BUILTIN, FilesystemMode.parse("builtin"));
        assertEquals(FilesystemMode.BUILTIN, FilesystemMode.parse(" Built-In "));
        assertEquals(FilesystemMode.NATIVE, FilesystemMode.parse("native"));
        assertEquals(FilesystemMode.NATIVE, FilesystemMode.parse("NATIVE"));
    }

    @Test
    public void syspropResolvesThroughAiConfig() {
        assertEquals(FilesystemMode.AUTO, AiConfig.resolveFilesystemMode(),
                "unset must default to AUTO");

        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "builtin");
        assertEquals(FilesystemMode.BUILTIN, AiConfig.resolveFilesystemMode());

        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "native");
        assertEquals(FilesystemMode.NATIVE, AiConfig.resolveFilesystemMode());

        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "not-a-mode");
        assertEquals(FilesystemMode.AUTO, AiConfig.resolveFilesystemMode(),
                "malformed values must collapse to AUTO, never throw");
    }
}
