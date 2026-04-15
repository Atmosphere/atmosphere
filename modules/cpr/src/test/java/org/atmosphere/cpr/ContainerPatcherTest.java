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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerPatcherTest {

    private String originalTomcatProperty;

    @BeforeEach
    void saveOriginalProperty() {
        originalTomcatProperty = System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
    }

    @AfterEach
    void restoreOriginalProperty() {
        if (originalTomcatProperty == null) {
            System.clearProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
        } else {
            System.setProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE, originalTomcatProperty);
        }
    }

    @Test
    void patchSetsTomcatPropertyWhenNotSet() {
        System.clearProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
        var framework = new AtmosphereFramework();

        ContainerPatcher.patch(framework);

        assertEquals("false", System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE));
    }

    @Test
    void patchOverwritesPropertyWhenSetToTrue() {
        System.setProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE, "true");
        var framework = new AtmosphereFramework();

        ContainerPatcher.patch(framework);

        assertEquals("false", System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE));
    }

    @Test
    void patchLeavesPropertyUnchangedWhenAlreadyFalse() {
        System.setProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE, "false");
        var framework = new AtmosphereFramework();

        ContainerPatcher.patch(framework);

        assertEquals("false", System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE));
    }

    @Test
    void patchDoesNothingWhenDisabled() {
        System.clearProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
        var framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.DISABLE_CONTAINER_PATCHING, "true");

        ContainerPatcher.patch(framework);

        assertNull(System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE));
    }

    @Test
    void patchAppliesWhenDisabledSetToFalse() {
        System.clearProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
        var framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.DISABLE_CONTAINER_PATCHING, "false");

        ContainerPatcher.patch(framework);

        assertEquals("false", System.getProperty(ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE));
    }

    @Test
    void tomcatStrictServletComplianceConstantValue() {
        assertEquals("org.apache.catalina.STRICT_SERVLET_COMPLIANCE",
                ContainerPatcher.TOMCAT_STRICT_SERVLET_COMPLIANCE);
    }
}
