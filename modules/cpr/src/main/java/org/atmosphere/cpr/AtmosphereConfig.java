/*
 * Copyright 2012 Sebastien Dionne
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

import org.atmosphere.config.AtmosphereHandlerConfig;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.atmosphere.cpr.ApplicationConfig.DEFAULT_NAMED_DISPATCHER;

/**
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class AtmosphereConfig {

    private final List<AtmosphereHandlerConfig> atmosphereHandler = new ArrayList<AtmosphereHandlerConfig>();

    private boolean supportSession = true;
    private String dispatcherName = DEFAULT_NAMED_DISPATCHER;
    private final AtmosphereFramework framework;
    private final Map<String, Object> properties = new HashMap<String, Object>();

    public AtmosphereConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    public List<AtmosphereHandlerConfig> getAtmosphereHandler() {
        return atmosphereHandler;
    }

    public AtmosphereFramework framework() {
        return framework;
    }

    public ServletConfig getServletConfig() {
        return framework.getServletConfig();
    }

    public ServletContext getServletContext() {
        return framework.getServletContext();
    }

    public String getWebServerName() {
        return framework.getCometSupport().getContainerName();
    }

    public Map<String, AtmosphereFramework.AtmosphereHandlerWrapper> handlers() {
        return framework.getAtmosphereHandlers();
    }

    public String getInitParameter(String name) {
        try {
            return framework.getServletConfig().getInitParameter(name);
        } catch (Throwable ex) {
            // Don't fail if Tomcat crash on startup with an NPE
            return null;
        }
    }

    public Enumeration<String> getInitParameterNames() {
        return framework().getServletConfig().getInitParameterNames();
    }

    public boolean isSupportSession() {
        return supportSession;
    }

    public void setSupportSession(boolean supportSession) {
        this.supportSession = supportSession;
    }

    /**
     * Return an instance of a {@link DefaultBroadcasterFactory}
     *
     * @return an instance of a {@link DefaultBroadcasterFactory}
     */
    public BroadcasterFactory getBroadcasterFactory() {
        return framework.getBroadcasterFactory();
    }

    public String getDispatcherName() {
        return dispatcherName;
    }

    public void setDispatcherName(String dispatcherName) {
        this.dispatcherName = dispatcherName;
    }

    public Map<String, Object> properties() {
        return properties;
    }

}
