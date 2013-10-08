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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import java.util.Set;

@HandlesTypes({})
public class AtmosphereInitializer implements ServletContainerInitializer {

    private final Logger logger = LoggerFactory.getLogger(AtmosphereInitializer.class);

    private AtmosphereFramework framework;

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext c) throws ServletException {
        Object container = c.getAttribute("javax.websocket.server.ServerContainer");
        // Jetty 9.1.0 return null, so let the Servlet.init() do the work for us.
        if (container != null && framework == null) {
            logger.trace("Initializing AtmosphereFramework");
            framework = new AtmosphereFramework(false, true);
            try {
                framework.init(c);
                c.setAttribute(AtmosphereFramework.class.getName(), framework);
            } catch (ServletException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
