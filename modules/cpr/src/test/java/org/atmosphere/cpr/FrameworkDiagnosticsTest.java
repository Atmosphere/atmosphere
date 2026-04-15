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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkDiagnosticsTest {

    @Test
    void newerMajorVersion() {
        assertTrue(FrameworkDiagnostics.isNewerVersion("5.0.0", "4.0.0"));
    }

    @Test
    void newerMinorVersion() {
        assertTrue(FrameworkDiagnostics.isNewerVersion("4.1.0", "4.0.0"));
    }

    @Test
    void newerPatchVersion() {
        assertTrue(FrameworkDiagnostics.isNewerVersion("4.0.2", "4.0.1"));
    }

    @Test
    void sameVersionNotNewer() {
        assertFalse(FrameworkDiagnostics.isNewerVersion("4.0.1", "4.0.1"));
    }

    @Test
    void olderVersionNotNewer() {
        assertFalse(FrameworkDiagnostics.isNewerVersion("3.9.0", "4.0.0"));
    }

    @Test
    void differentLengthVersions() {
        assertTrue(FrameworkDiagnostics.isNewerVersion("4.0.1.1", "4.0.1"));
    }

    @Test
    void shorterNewerVersion() {
        assertTrue(FrameworkDiagnostics.isNewerVersion("5.0", "4.9.9"));
    }

    @Test
    void shorterOlderVersion() {
        assertFalse(FrameworkDiagnostics.isNewerVersion("3.0", "4.0.0"));
    }
}
