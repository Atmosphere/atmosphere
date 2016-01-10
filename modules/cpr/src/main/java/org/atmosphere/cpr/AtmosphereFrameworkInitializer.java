/*
 * Copyright 2014 Jeanfrancois Arcand
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

import org.atmosphere.util.IOUtils;
import org.atmosphere.util.VoidServletConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.util.Map;

import static org.atmosphere.cpr.ApplicationConfig.META_SERVICE_PATH;
import static org.atmosphere.cpr.AtmosphereFramework.META_SERVICE;

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
        return configureFramework(sc, true, false, AtmosphereFramework.class);
    }

    public AtmosphereFrameworkInitializer configureFramework(ServletConfig sc, boolean init, boolean useNative, Class<? extends AtmosphereFramework> frameworkClass) throws ServletException {
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
                framework = newAtmosphereFramework(frameworkClass, isFilter, autoDetectHandlers);
            }
        }
        framework.setUseNativeImplementation(useNative);
        if (init) framework.init(sc);
        return this;
    }

    protected AtmosphereFramework newAtmosphereFramework(Class<? extends AtmosphereFramework> frameworkClass) {
        return AtmosphereFrameworkInitializer.newAtmosphereFramework(frameworkClass, isFilter, autoDetectHandlers);
    }

    protected static AtmosphereFramework newAtmosphereFramework(Class<? extends AtmosphereFramework> frameworkClass, boolean isFilter, boolean autoDetectHandlers, final ServletContext c) {
        AtmosphereFramework framework;
        try {
            framework = (AtmosphereFramework) frameworkClass.getDeclaredConstructor(
                    new Class[]{boolean.class, boolean.class}).newInstance(isFilter, autoDetectHandlers);
        } catch (Exception e) {
            logger.error("", e);
            framework = new AtmosphereFramework(isFilter, autoDetectHandlers);
        }

        if (c != null) {
            framework.servletConfig(new VoidServletConfig(framework.initParams) {
                @Override
                public ServletContext getServletContext() {
                    return c;
                }
            }, false);
        }

        return framework;
    }

    protected static AtmosphereFramework newAtmosphereFramework(Class<? extends AtmosphereFramework> frameworkClass, boolean isFilter, boolean autoDetectHandlers) {
        return newAtmosphereFramework(frameworkClass, isFilter,autoDetectHandlers, null);
    }

    public static AtmosphereFramework newAtmosphereFramework(ServletContext sc, boolean isFilter, boolean autoDetectHandlers) {

        String metaServicePath = sc.getInitParameter(META_SERVICE_PATH) == null ? META_SERVICE : sc.getInitParameter(META_SERVICE_PATH);
        try {
            final Map<String, AtmosphereFramework.MetaServiceAction> config =
                    IOUtils.readServiceFile(metaServicePath + AtmosphereFramework.class.getName());
            sc.setAttribute(AtmosphereFramework.MetaServiceAction.class.getName(), config);

            for (final Map.Entry<String, AtmosphereFramework.MetaServiceAction> action : config.entrySet()) {
                final Class c = IOUtils.loadClass(AtmosphereFramework.class, action.getKey());
                if (AtmosphereFramework.class.isAssignableFrom(c)) {
                    logger.info("Found a definition of AtmosphereFramework {} under {}", c, metaServicePath);
                    return newAtmosphereFramework(c, isFilter, autoDetectHandlers);
                }
            }
        } catch (Exception ex) {
            logger.error("", ex);
        }
        return newAtmosphereFramework(AtmosphereFramework.class, isFilter, autoDetectHandlers, sc);
    }

    public AtmosphereFramework framework() {
        if (framework == null) {
            framework = newAtmosphereFramework(AtmosphereFramework.class, isFilter, autoDetectHandlers);
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
