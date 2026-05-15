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
package org.atmosphere.quarkus.admin.deployment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminProcessorTest {

    @Test
    void nativeImageResourcesIncludeAdminAndConsoleEntryPoints() {
        var resources = new AdminProcessor().registerConsoleResources().getResources();

        assertTrue(resources.contains("META-INF/resources/admin/index.html"));
        assertTrue(resources.contains("META-INF/resources/atmosphere/console/index.html"));
        assertTrue(resources.contains("META-INF/resources/atmosphere/console/assets/index-D4Ey4XUD.js"));
        assertTrue(resources.contains("META-INF/resources/atmosphere/console/assets/index-5mdPW76Z.css"));
    }
}
