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
package org.atmosphere.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void rawVersionNotNull() {
        assertNotNull(Version.getRawVersion());
        assertFalse(Version.getRawVersion().isEmpty());
    }

    @Test
    void dottedVersionNotNull() {
        assertNotNull(Version.getDotedVersion());
    }

    @Test
    void majorVersionPositive() {
        assertTrue(Version.getMajorVersion() >= 0,
                "Major version should be non-negative");
    }

    @Test
    void minorVersionPositive() {
        assertTrue(Version.getMinorVersion() >= 0,
                "Minor version should be non-negative");
    }

    @Test
    void microVersionPositive() {
        assertTrue(Version.getMicroVersion() >= 0,
                "Micro version should be non-negative");
    }

    @Test
    void equalVersionMatchesCurrent() {
        assertTrue(Version.equalVersion(
                Version.getMajorVersion(), Version.getMinorVersion()));
    }

    @Test
    void equalVersionReturnsFalseForDifferent() {
        assertFalse(Version.equalVersion(999, 999));
    }

    @Test
    void rawVersionContainsMajorAndMinor() {
        var raw = Version.getRawVersion();
        assertTrue(raw.contains("."), "Version should contain dots: " + raw);
    }
}
