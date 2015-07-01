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

import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.util.UUIDProvider;
import org.atmosphere.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class contains information about the current state of the {@link AtmosphereFramework}. You can also
 * register a {@link ShutdownHook}.
 *
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 * @author Jeanfrancois Arcand
 */
public class AtmosphereConfig {

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereConfig.class);

    private List<AtmosphereHandlerConfig> atmosphereHandlerConfig = new ArrayList<AtmosphereHandlerConfig>();

    private boolean supportSession;
    private boolean sessionTimeoutRemovalAllowed;
    private boolean throwExceptionOnCloned;
    private AtmosphereFramework framework;
    private final Map<String, Object> properties = new ConcurrentHashMap<String, Object>();
    protected List<ShutdownHook> shutdownHooks = new ArrayList<ShutdownHook>();
    protected List<StartupHook> startUpHook = new ArrayList<StartupHook>();

    protected AtmosphereConfig(AtmosphereFramework framework) {
        this.framework = framework;
    }

    public List<AtmosphereHandlerConfig> getAtmosphereHandlerConfig() {
        return atmosphereHandlerConfig;
    }

    /**
     * Return the {@link AtmosphereFramework}.
     *
     * @return the {@link AtmosphereFramework}
     */
    public AtmosphereFramework framework() {
        return framework;
    }

    /**
     * Return the {@link ServletConfig}.
     *
     * @return {@link ServletConfig}
     */
    public ServletConfig getServletConfig() {
        return framework.getServletConfig();
    }

    /**
     * Return the {@link ServletContext}.
     *
     * @return {@link ServletContext}
     */
    public ServletContext getServletContext() {
        return framework.getServletContext();
    }

    /**
     * Return the current WebServer used.
     *
     * @return the current WebServer used
     */
    public String getWebServerName() {
        return framework.getAsyncSupport().getContainerName();
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper}s.
     *
     * @return the list of {@link org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper}
     */
    public Map<String, AtmosphereFramework.AtmosphereHandlerWrapper> handlers() {
        return framework.getAtmosphereHandlers();
    }

    /**
     * Return the value of the init params defined in web.xml or application.xml.
     *
     * @param name the name
     * @return the value for the init parameter if defined
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
     * @return the list of init params defined in web.xml or application.xml for the servlet
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
     * Enable/Disable {@link javax.servlet.http.HttpSession}.
     *
     * @param supportSession true to enable, false to disable
     */
    public void setSupportSession(boolean supportSession) {
        this.supportSession = supportSession;
    }

    /**
     * Allow HTTP session timeout removal when session support is active
     *
     * @return HTTP session timeout removal allowed.
     */
    public boolean isSessionTimeoutRemovalAllowed() {
        return sessionTimeoutRemovalAllowed;
    }

    /**
     * Enable/Disable {@link javax.servlet.http.HttpSession} timeout removal when a connection exists.
     *
     * @param sessionTimeoutRemovalAllowed true to enable, false to disable
     */
    public void setSessionTimeoutRemovalAllowed(boolean sessionTimeoutRemovalAllowed) {
        this.sessionTimeoutRemovalAllowed = sessionTimeoutRemovalAllowed;
    }

    /**
     * Is cloned request throws exception.
     *
     * @return Cloned Request's exception supported.
     */
    public boolean isThrowExceptionOnCloned() {
        return this.throwExceptionOnCloned;
    }

    /**
     * Enable/Disable Exception on cloned request.
     *
     * @param throwExceptionOnCloned
     */
    public void setThrowExceptionOnCloned(boolean throwExceptionOnCloned) {
        this.throwExceptionOnCloned = throwExceptionOnCloned;
    }

    /**
     * Return an instance of {@link DefaultBroadcasterFactory}.
     *
     * @return an instance of {@link DefaultBroadcasterFactory}
     */
    public BroadcasterFactory getBroadcasterFactory() {
        return framework.getBroadcasterFactory();
    }

    /**
     * Return the {@link Map} of Applications's properties.
     *
     * @return the {@link Map} of Applications's properties
     */
    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * Invoke {@link ShutdownHook}s.
     */
    protected void destroy() {
        for (ShutdownHook h : shutdownHooks) {
            try {
                h.shutdown();
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
        properties.clear();
        shutdownHooks.clear();
        startUpHook.clear();
        atmosphereHandlerConfig.clear();
    }

    /**
     * Invoke {@link ShutdownHook}s.
     */
    protected void initComplete() {
        for (StartupHook h : startUpHook) {
            try {
                h.started(framework);
            } catch (Exception ex) {
                logger.warn("", ex);
            }
        }
        startUpHook.clear();
    }

    /**
     * Add a {@link ShutdownHook}.
     *
     * @param s a {@link ShutdownHook}
     * @return this
     */
    public AtmosphereConfig shutdownHook(ShutdownHook s) {
        shutdownHooks.add(s);
        return this;
    }

    /**
     * Add a {@link StartupHook}. If the {@link AtmosphereFramework#isInit} return true, the
     * StartupHook will be executed immediately.
     *
     * @param s a {@link StartupHook}
     * @return this
     */
    public AtmosphereConfig startupHook(StartupHook s) {
        if (framework().isInit) {
            s.started(framework);
        } else {
            startUpHook.add(s);
        }
        return this;
    }

    /**
     * Return an init-param, or its default value.
     *
     * @param key
     * @param defaultValue
     * @return an init-param, or its default value.
     */
    public String getInitParameter(String key, String defaultValue) {
        String s = getInitParameter(key);
        if (s == null) {
            return defaultValue;
        }
        return s;
    }

    /**
     * Return an init-param, or its default value.
     *
     * @param key
     * @param defaultValue
     * @return an init-param, or its default value.
     */
    public boolean getInitParameter(String key, boolean defaultValue) {
        String s = getInitParameter(key);
        if (s == null) {
            return defaultValue;
        }
        return Boolean.valueOf(s);
    }

    /**
     * Return an init-param, or its default value.
     *
     * @param key
     * @param defaultValue
     * @return an init-param, or its default value.
     */
    public int getInitParameter(String key, int defaultValue) {
        String s = getInitParameter(key);
        if (s == null) {
            return defaultValue;
        }
        return Integer.valueOf(s);
    }

    /**
     * Return the {@link AtmosphereResourceFactory}
     *
     * @return the AtmosphereResourceFactory
     */
    public AtmosphereResourceFactory resourcesFactory() {
        return framework.atmosphereFactory();
    }

    /**
     * Return the {@link DefaultMetaBroadcaster}
     *
     * @return the MetaBroadcaster
     */
    public MetaBroadcaster metaBroadcaster() {
        return framework.metaBroadcaster();
    }

    /**
     * Return the {@link AtmosphereResourceSessionFactory}
     *
     * @return the AtmosphereResourceSessionFactory
     */
    public AtmosphereResourceSessionFactory sessionFactory() {
        return framework.sessionFactory();
    }

    /**
     * Return the {@link org.atmosphere.util.UUIDProvider}
     *
     * @return {@link org.atmosphere.util.UUIDProvider}
     */
    public UUIDProvider uuidProvider() {
        return framework.uuidProvider();
    }

    /**
     * Return the {@link WebSocketFactory}
     *
     * @return the {@link WebSocketFactory}
     */
    public WebSocketFactory websocketFactory() {
        return framework.webSocketFactory();
    }

    /**
     * A shutdown hook that will be called when the {@link AtmosphereFramework#destroy} method gets invoked. An
     * Application can register one of more hooks.
     */
    public static interface ShutdownHook {

        void shutdown();
    }

    /**
     * A Startup hook that will be called when the {@link AtmosphereFramework#init} method complete. An
     * Application can register one of more hooks.
     */
    public static interface StartupHook {

        void started(AtmosphereFramework framework);
    }

    public AtmosphereConfig populate(AtmosphereConfig config) {
        atmosphereHandlerConfig = config.atmosphereHandlerConfig;

        supportSession = config.supportSession;
        sessionTimeoutRemovalAllowed = config.sessionTimeoutRemovalAllowed;
        throwExceptionOnCloned = config.throwExceptionOnCloned;
        framework = config.framework;
        properties.putAll(config.properties);
        shutdownHooks = config.shutdownHooks;
        startUpHook = config.startUpHook;
        return this;
    }
}
