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

import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper;

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

    private final List<org.atmosphere.config.AtmosphereHandler> atmosphereHandler = new ArrayList<org.atmosphere.config.AtmosphereHandler>();

    private boolean supportSession = true;
    private BroadcasterFactory broadcasterFactory;
    private String dispatcherName = DEFAULT_NAMED_DISPATCHER;
    private final AtmosphereServlet atmosphereServlet;
    // for custom properties
    private final Map<String, Object> properties = new HashMap<String, Object>();

    public AtmosphereConfig(AtmosphereServlet atmosphereServlet) {
        this.atmosphereServlet = atmosphereServlet;
    }

    public List<org.atmosphere.config.AtmosphereHandler> getAtmosphereHandler() {
        return atmosphereHandler;
    }

    public AtmosphereServlet getServlet() {
        return atmosphereServlet;
    }

    public ServletConfig getServletConfig() {
        return atmosphereServlet.getServletConfig();
    }

    public ServletContext getServletContext() {
        return atmosphereServlet.getServletContext();
    }

    public String getWebServerName() {
        return atmosphereServlet.getCometSupport().getContainerName();
    }

    public Map<String, AtmosphereHandlerWrapper> handlers() {
        return atmosphereServlet.getAtmosphereHandlers();
    }

    public String getInitParameter(String name) {
        // First looks locally
        String s = atmosphereServlet.initParams.get(name);
        if (s != null) {
            return s;
        }

        try {
            return atmosphereServlet.getInitParameter(name);
        } catch (Throwable ex) {
            // Don't fail if Tomcat crash on startup with an NPE
            return null;
        }
    }

    public Enumeration<String> getInitParameterNames() {
        // First looks locally
        Map<String, String> map = new HashMap<String, String>(atmosphereServlet.initParams);

        Enumeration en = atmosphereServlet.getInitParameterNames();
        while (en.hasMoreElements()) {
            String name = (String) en.nextElement();
            if (!map.containsKey(name)) {
                map.put(name, name);
            }
        }

        Vector<String> v = new Vector<String>(map.keySet());

        return v.elements();
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
        return broadcasterFactory;
    }

    public void setBroadcasterFactory(BroadcasterFactory broadcasterFactory) {
        this.broadcasterFactory = broadcasterFactory;
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
