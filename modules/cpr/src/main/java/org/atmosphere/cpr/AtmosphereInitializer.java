/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.container.JSR356AsyncSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

@HandlesTypes({})
public class AtmosphereInitializer implements ServletContainerInitializer {

    private final Logger logger = LoggerFactory.getLogger(AtmosphereInitializer.class);

    private AtmosphereFramework framework;

    @Override
    public void onStartup(Set<Class<?>> classes, final ServletContext c) throws ServletException {
        logger.trace("Initializing AtmosphereFramework");

        framework = (AtmosphereFramework) c.getAttribute(AtmosphereFramework.class.getName());
        if (framework == null) {
            framework = new AtmosphereFramework(false, true);
            // Hack to make jsr356 works. Pretty ugly.
            DefaultAsyncSupportResolver resolver = new DefaultAsyncSupportResolver(framework.getAtmosphereConfig());
            List<Class<? extends AsyncSupport>> l = resolver.detectWebSocketPresent(false, true);

            if (l.size() == 0 && resolver.testClassExists(DefaultAsyncSupportResolver.JSR356_WEBSOCKET)) {
                framework.setAsyncSupport(new JSR356AsyncSupport(new AtmosphereConfig(framework) {
                    public ServletContext getServletContext() {
                        return c;
                    }

                    public String getInitParameter(String name) {
                        return c.getInitParameter(name);
                    }

                    public Enumeration<String> getInitParameterNames() {
                        return c.getInitParameterNames();
                    }
                }));
            }

            c.setAttribute(AtmosphereFramework.class.getName(), framework);
        }
    }
}
