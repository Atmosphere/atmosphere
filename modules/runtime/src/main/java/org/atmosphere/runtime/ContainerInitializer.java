/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.runtime;

import org.atmosphere.container.JSR356AsyncSupport;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.Utils;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.atmosphere.runtime.ApplicationConfig.PROPERTY_SESSION_SUPPORT;

/**
 * Initializer for the AtmosphereFramework per servlet instance,
 * this initializer is called during web-application startup lifecycle (since Servlet 3.0).
 * If you need to disable automatic initialization take a look at the following switch:
 * <p/>
 * {@link org.atmosphere.runtime.ApplicationConfig}.DISABLE_ATMOSPHERE_INITIALIZER}
 */

@HandlesTypes({})
public class ContainerInitializer implements javax.servlet.ServletContainerInitializer {

    @Override
    public void onStartup(Set<Class<?>> classes, final ServletContext c) throws ServletException {
        c.log("Initializing AtmosphereFramework");
        for (Map.Entry<String, ? extends ServletRegistration> reg : c.getServletRegistrations().entrySet()) {
            String disableSwitchValue = reg.getValue().getInitParameter(ApplicationConfig.DISABLE_ATMOSPHERE_INITIALIZER);
            // check if AtmosphereInitializer is disabled via web.xml see: https://github.com/Atmosphere/atmosphere/issues/1695
            if (Boolean.parseBoolean(disableSwitchValue)) {
                c.log("Container managed initialization disabled for servlet: " + reg.getValue().getName());
                continue;
            }

            if (c.getAttribute(reg.getKey()) == null && IOUtils.isAtmosphere(reg.getValue().getClassName())) {
                final AtmosphereFramework framework = AtmosphereFrameworkInitializer.newAtmosphereFramework(c, false, true);
                // Hack to make jsr356 works. Pretty ugly.
                DefaultAsyncSupportResolver resolver = new DefaultAsyncSupportResolver(framework.getAtmosphereConfig());
                List<Class<? extends AsyncSupport>> l = resolver.detectWebSocketPresent(false, true);

                if (resolver.testClassExists(DefaultAsyncSupportResolver.JSR356_WEBSOCKET)) {
                    try {
                        framework.setAsyncSupport(new JSR356AsyncSupport(framework.getAtmosphereConfig(), c));
                    } catch (IllegalStateException ex) {
                        framework.initializationError(ex);
                    }
                }

                try {
                    c.addListener(new ServletRequestListener() {
                        @Override
                        public void requestDestroyed(ServletRequestEvent sre) {
                        }

                        @Override
                        public void requestInitialized(ServletRequestEvent sre) {
                            HttpServletRequest r = HttpServletRequest.class.cast(sre.getServletRequest());
                            AtmosphereConfig config = framework.getAtmosphereConfig();
                            if (config.isSupportSession() && Utils.webSocketEnabled(r)) {
                                r.getSession(config.getInitParameter(ApplicationConfig.PROPERTY_SESSION_CREATE, true));
                            }
                        }
                    });
                } catch (Throwable t) {
                    c.log("AtmosphereFramework : Unable to install WebSocket Session Creator", t);
                }

                try {
                    String s = c.getInitParameter(PROPERTY_SESSION_SUPPORT);
                    if (s != null) {
                        boolean sessionSupport = Boolean.valueOf(s);
                        if (sessionSupport && c.getMajorVersion() > 2) {
                            c.addListener(SessionSupport.class);
                            c.log("AtmosphereFramework : Installed " + SessionSupport.class);
                        }
                    }
                } catch (Throwable t) {
                    c.log("AtmosphereFramework : SessionSupport error. Make sure you also define {} as a listener in web.xml, see https://github.com/Atmosphere/atmosphere/wiki/Enabling-HttpSession-Support", t);
                }

                c.setAttribute(reg.getKey(), framework);
            }
        }
    }
}
