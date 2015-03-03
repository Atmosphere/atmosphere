/*
 * Copyright 2015 Async-IO.org
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

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

public class AtmosphereFrameworkInitializer {
    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFrameworkInitializer.class);

    protected AtmosphereFramework framework;
    protected boolean isFilter;
    protected boolean autoDetectHandlers;

    public AtmosphereFrameworkInitializer(boolean isFilter, boolean autoDetectHandlers) {
        this.isFilter = isFilter;
        this.autoDetectHandlers = autoDetectHandlers;
    }

    public AtmosphereFrameworkInitializer configureFramework(ServletConfig sc) throws ServletException {
        return configureFramework(sc, true, false);
    }

    public AtmosphereFrameworkInitializer configureFramework(ServletConfig sc, boolean init, boolean useNative) throws ServletException {
        if (framework == null) {
            if (sc.getServletContext().getMajorVersion() > 2) {
                try {
                    framework = (AtmosphereFramework) sc.getServletContext()
                            .getAttribute(sc.getServletContext().getServletRegistration(sc.getServletName()).getName());
                } catch (Exception ex) {
                    // Equinox throw an exception (NPE)
                    // WebLogic Crap => https://github.com/Atmosphere/atmosphere/issues/1569
                    if (UnsupportedOperationException.class.isAssignableFrom(ex.getClass())) {
                        logger.warn("WebLogic 12c unable to retrieve Servlet. Please make sure your servlet-name is 'AtmosphereServlet' " +
                                "or set org.atmosphere.servlet to the current value");
                        String name = sc.getInitParameter(ApplicationConfig.SERVLET_NAME);
                        if (name == null) {
                            name = AtmosphereServlet.class.getSimpleName();
                        }
                        framework = (AtmosphereFramework) sc.getServletContext().getAttribute(name);
                    } else {
                        logger.trace("", ex);
                    }
                }
            }

            if (framework == null) {
                framework = newAtmosphereFramework();
            }
        }
        framework.setUseNativeImplementation(useNative);
        if (init) framework.init(sc);
        return this;
    }

    protected AtmosphereFramework newAtmosphereFramework() {
        return new AtmosphereFramework(isFilter, autoDetectHandlers);
    }

    public AtmosphereFramework framework() {
        if (framework == null) {
            framework = newAtmosphereFramework();
        }
        return framework;
    }

    public void destroy() {
        if (framework != null) {
            framework.destroy();
            framework = null;
        }
    }
}
