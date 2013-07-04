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

import org.atmosphere.config.AtmosphereHandlerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class contains information about the current state of the {@link AtmosphereFramework}. You can also
 * register {@link ShutdownHook}.
 *
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 * @author Jeanfrancois Arcand
 */
public class AtmosphereConfig {
    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereConfig.class);

    private final List<AtmosphereHandlerConfig> atmosphereHandlerConfig = new ArrayList<AtmosphereHandlerConfig>();

    private boolean supportSession;
    private boolean throwExceptionOnCloned;
    private final AtmosphereFramework framework;
    private final Map<String, Object> properties = new HashMap<String, Object>();
    protected final List<ShutdownHook> shutdownHooks = new ArrayList<ShutdownHook>();

    public AtmosphereConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    public List<AtmosphereHandlerConfig> getAtmosphereHandlerConfig() {
        return atmosphereHandlerConfig;
    }

    /**
     * Return the {@link AtmosphereFramework}
     *
     * @return the {@link AtmosphereFramework}
     */
    public AtmosphereFramework framework() {
        return framework;
    }

    /**
     * Return the {@link ServletConfig}
     *
     * @return {@link ServletConfig}
     */
    public ServletConfig getServletConfig() {
        return framework.getServletConfig();
    }

    /**
     * Return the {@link ServletContext}
     *
     * @return {@link ServletContext}
     */
    public ServletContext getServletContext() {
        return framework.getServletContext();
    }

    /**
     * Return the current WebServer used.
     *
     * @return the current WebServer used.
     */
    public String getWebServerName() {
        return framework.getAsyncSupport().getContainerName();
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper}
     *
     * @return the list of {@link org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper}
     */
    public Map<String, AtmosphereFramework.AtmosphereHandlerWrapper> handlers() {
        return framework.getAtmosphereHandlers();
    }

    /**
     * Return the value of the init paramsdefined in web.xml or application.xml
     *
     * @param name the name
     * @return the list of init params defined in web.xml or application.xml
     */
    public String getInitParameter(String name) {
        try {
            return framework.getServletConfig().getInitParameter(name);
        } catch (Throwable ex) {
            // Don't fail if Tomcat crash on startup with an NPE
            return null;
        }
    }

    /**
     * Return all init param.
     *
     * @return
     */
    public Enumeration<String> getInitParameterNames() {
        return framework().getServletConfig().getInitParameterNames();
    }

    /**
     * Is {@link javax.servlet.http.HttpSession} supported.
     *
     * @return {@link javax.servlet.http.HttpSession} supported.
     */
    public boolean isSupportSession() {
        return supportSession;
    }

    /**
     * Enable/Disable {@link javax.servlet.http.HttpSession}
     *
     * @param supportSession
     */
    public void setSupportSession(boolean supportSession) {
        this.supportSession = supportSession;
    }

    /**
     * Is cloned request throws exception
     *
     * @return Cloned Request's exception  supported.
     */
    public boolean isThrowExceptionOnCloned() {
        return this.throwExceptionOnCloned;
    }

    /**
     * Enable/Disable Exception on cloned request
     *
     * @param throwExceptionOnCloned
     */
    public void setThrowExceptionOnCloned(boolean throwExceptionOnCloned) {
        this.throwExceptionOnCloned = throwExceptionOnCloned;
    }

    /**
     * Return an instance of a {@link DefaultBroadcasterFactory}
     *
     * @return an instance of a {@link DefaultBroadcasterFactory}
     */
    public BroadcasterFactory getBroadcasterFactory() {
        return framework.getBroadcasterFactory();
    }

    /**
     * Return the {@link Map} of Applications's Properties.
     *
     * @return the {@link Map} of Applications's Properties.
     */
    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * Invoke {@link ShutdownHook}
     */
    protected void destroy() {
        for (ShutdownHook h : shutdownHooks) {
            try {
                h.shutdown();
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
    }

    /**
     * Add a {@link ShutdownHook}
     *
     * @param s a {@link ShutdownHook}
     * @return this
     */
    public AtmosphereConfig shutdownHook(ShutdownHook s) {
        shutdownHooks.add(s);
        return this;
    }

    /**
     * A shutdown hook that will be called when the {@link AtmosphereFramework#destroy} method gets invoked. An
     * Application can register one of more hook.
     */
    public static interface ShutdownHook {

        void shutdown();
    }
}
