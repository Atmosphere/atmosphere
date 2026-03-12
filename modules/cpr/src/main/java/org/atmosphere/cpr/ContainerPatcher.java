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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isolates JVM-global system property mutations that work around container-specific issues.
 * <p>
 * During initialization, Atmosphere may need to set system properties to disable strict
 * compliance modes in certain servlet containers. This class centralizes that logic so it
 * can be clearly documented, logged, and optionally disabled via
 * {@link ApplicationConfig#DISABLE_CONTAINER_PATCHING}.
 * <p>
 * <strong>Properties modified:</strong>
 * <ul>
 *     <li>{@code org.apache.catalina.STRICT_SERVLET_COMPLIANCE} &mdash; set to {@code "false"}
 *     to prevent Tomcat from enforcing strict servlet spec compliance, which can interfere
 *     with Atmosphere's async/comet handling.</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
final class ContainerPatcher {

    private static final Logger logger = LoggerFactory.getLogger(ContainerPatcher.class);

    /**
     * Tomcat system property that controls strict servlet specification compliance.
     * When enabled, Tomcat enforces stricter request/response behavior that can break
     * Atmosphere's async processing model.
     */
    static final String TOMCAT_STRICT_SERVLET_COMPLIANCE =
            "org.apache.catalina.STRICT_SERVLET_COMPLIANCE";

    private ContainerPatcher() {
        // utility class
    }

    /**
     * Apply container-specific system property patches unless disabled via init-param.
     * <p>
     * If the init-param {@link ApplicationConfig#DISABLE_CONTAINER_PATCHING} is set to
     * {@code "true"}, no system properties will be modified.
     * <p>
     * If a system property is already set to the desired value, it is left untouched
     * and no log message is emitted for that property.
     *
     * @param framework the AtmosphereFramework whose init-params are consulted
     */
    static void patch(AtmosphereFramework framework) {
        if (isDisabled(framework)) {
            logger.debug("Container patching disabled via {}",
                    ApplicationConfig.DISABLE_CONTAINER_PATCHING);
            return;
        }

        setPropertyIfAbsent(TOMCAT_STRICT_SERVLET_COMPLIANCE, "false");
    }

    private static boolean isDisabled(AtmosphereFramework framework) {
        var value = framework.initParams.get(ApplicationConfig.DISABLE_CONTAINER_PATCHING);
        return Boolean.parseBoolean(value);
    }

    /**
     * Sets a system property only if it is not already set to the desired value.
     * Logs the modification at info level so operators can see what was changed.
     */
    private static void setPropertyIfAbsent(String key, String desiredValue) {
        var currentValue = System.getProperty(key);
        if (desiredValue.equals(currentValue)) {
            return;
        }

        if (currentValue != null) {
            logger.info("Overwriting system property {} from '{}' to '{}'",
                    key, currentValue, desiredValue);
        } else {
            logger.info("Setting system property {} to '{}'", key, desiredValue);
        }

        System.setProperty(key, desiredValue);
    }
}
