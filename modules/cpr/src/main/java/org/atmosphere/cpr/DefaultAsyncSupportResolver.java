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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.GlassFishServ30WebSocketSupport;
import org.atmosphere.container.GlassFishWebSocketSupport;
import org.atmosphere.container.GlassFishv2CometSupport;
import org.atmosphere.container.Grizzly2CometSupport;
import org.atmosphere.container.Grizzly2WebSocketSupport;
import org.atmosphere.container.GrizzlyCometSupport;
import org.atmosphere.container.GrizzlyServlet30WebSocketSupport;
import org.atmosphere.container.JBossAsyncSupportWithWebSocket;
import org.atmosphere.container.JBossWebCometSupport;
import org.atmosphere.container.JSR356AsyncSupport;
import org.atmosphere.container.Jetty7CometSupport;
import org.atmosphere.container.Jetty9AsyncSupportWithWebSocket;
import org.atmosphere.container.JettyAsyncSupportWithWebSocket;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.container.JettyServlet30AsyncSupportWithWebSocket;
import org.atmosphere.container.NettyCometSupport;
import org.atmosphere.container.Servlet30CometSupport;
import org.atmosphere.container.Tomcat7AsyncSupportWithWebSocket;
import org.atmosphere.container.Tomcat7CometSupport;
import org.atmosphere.container.Tomcat7Servlet30SupportWithWebSocket;
import org.atmosphere.container.TomcatCometSupport;
import org.atmosphere.container.WebLogicServlet30WithWebSocket;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

/**
 * This is the default implementation of @link {AsyncSupportResolver}.
 *
 * @author Viktor Klang
 */
public class DefaultAsyncSupportResolver implements AsyncSupportResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAsyncSupportResolver.class);

    public final static String SERVLET_30 = "javax.servlet.AsyncListener";
    public final static String GLASSFISH_V2 = "com.sun.enterprise.web.PEWebContainer";
    public final static String TOMCAT_7 = "org.apache.catalina.comet.CometFilterChain";
    public final static String TOMCAT_WEBSOCKET = "org.apache.coyote.http11.upgrade.UpgradeInbound";
    public final static String TOMCAT = "org.apache.coyote.http11.Http11NioProcessor";
    public final static String JBOSS_5 = "org.jboss.";
    public final static String JETTY = "org.mortbay.util.ajax.Continuation";
    public final static String JETTY_7 = "org.eclipse.jetty.servlet.ServletContextHandler";
    public final static String JETTY_8 = "org.eclipse.jetty.continuation.Servlet3Continuation";
    public final static String JETTY_9 = "org.eclipse.jetty.websocket.api.WebSocketPolicy";
    public final static String GRIZZLY = "com.sun.grizzly.http.servlet.ServletAdapter";
    public final static String GRIZZLY2 = "org.glassfish.grizzly.http.servlet.ServletHandler";
    public final static String JBOSSWEB = "org.apache.catalina.connector.HttpEventImpl";
    public final static String GRIZZLY_WEBSOCKET = "com.sun.grizzly.websockets.WebSocketEngine";
    public final static String GRIZZLY2_WEBSOCKET = "org.glassfish.grizzly.websockets.WebSocketEngine";
    public final static String NETTY = "org.jboss.netty.channel.Channel";
    public final static String JBOSS_AS7_WEBSOCKET = "org.atmosphere.jboss.as.websockets.servlet.WebSocketServlet";
    public final static String JSR356_WEBSOCKET = "javax.websocket.Endpoint";
    public final static String WEBLOGIC_WEBSOCKET = "weblogic.websocket.annotation.WebSocket";
    public final static String HK2 = "org.glassfish.hk2.utilities.reflection.ReflectionHelper";

    private final AtmosphereConfig config;

    private final boolean suppress356;

    public DefaultAsyncSupportResolver(final AtmosphereConfig config) {
        this.config = config;
        this.suppress356 = 
            Boolean.parseBoolean(config.getInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356));
    }

    /**
     * Convenience method that tests if a class with the given FQN is present on the classpath.
     *
     * @param testClass
     * @return true if the class is present
     */
    protected boolean testClassExists(final String testClass) {
        try {
            final boolean exists = testClass != null && testClass.length() > 0 && IOUtils.loadClass(null, testClass) != null;
            logger.debug(exists ? "Found {}" : "Not found {}", testClass);
            return exists; 
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Returns a list of comet support by containers available on the classpath.
     *
     * @return
     */
    public List<Class<? extends AsyncSupport>> detectContainersPresent() {
        return new LinkedList<Class<? extends AsyncSupport>>() {
            {
                if (testClassExists(GLASSFISH_V2))
                    add(GlassFishv2CometSupport.class);

                if (testClassExists(JETTY_9))
                    add(Jetty7CometSupport.class);

                if (testClassExists(JETTY_8))
                    add(Jetty7CometSupport.class);

                if (testClassExists(JETTY_7))
                    add(Jetty7CometSupport.class);

                if (testClassExists(JETTY))
                    add(JettyCometSupport.class);

                if (testClassExists(JBOSSWEB))
                    add(JBossWebCometSupport.class);

                if (testClassExists(TOMCAT_7))
                    add(Tomcat7CometSupport.class);

                if (testClassExists(TOMCAT) || testClassExists(JBOSS_5))
                    add(TomcatCometSupport.class);

                if (testClassExists(GRIZZLY))
                    add(GrizzlyCometSupport.class);

                if (testClassExists(GRIZZLY2))
                    add(Grizzly2CometSupport.class);

                if (testClassExists(NETTY))
                    add(NettyCometSupport.class);
            }
        };
    }

    public List<Class<? extends AsyncSupport>> detectWebSocketPresent(final boolean useNativeIfPossible, final boolean useServlet30Async) {

        return new LinkedList<Class<? extends AsyncSupport>>() {
            {
                if (useServlet30Async && !useNativeIfPossible) {

                    if (!suppress356 && testClassExists(JSR356_WEBSOCKET)) {
                        add(JSR356AsyncSupport.class);
                    } else {

                        if (testClassExists(TOMCAT_WEBSOCKET))
                            add(Tomcat7Servlet30SupportWithWebSocket.class);

                        if (testClassExists(JETTY_9))
                            add(Jetty9AsyncSupportWithWebSocket.class);

                        if (testClassExists(JETTY_8))
                            add(JettyServlet30AsyncSupportWithWebSocket.class);

                        if (testClassExists(GRIZZLY2_WEBSOCKET))
                            add(GlassFishServ30WebSocketSupport.class);

                        if (testClassExists(GRIZZLY_WEBSOCKET))
                            add(GrizzlyServlet30WebSocketSupport.class);

                        if (testClassExists(WEBLOGIC_WEBSOCKET) && !testClassExists(HK2)) {
                            logger.warn("***************************************************************************************************");
                            logger.warn("WebLogic WebSocket detected and will be deployed under the hardcoded path <<application-name>>/ws/*");
                            logger.warn("***************************************************************************************************");
                            add(WebLogicServlet30WithWebSocket.class);
                        }
                    }
                } else {
                    if (!suppress356 && testClassExists(JSR356_WEBSOCKET)) {
                        add(JSR356AsyncSupport.class);
                    } else {
                        if (testClassExists(TOMCAT_WEBSOCKET))
                            add(Tomcat7AsyncSupportWithWebSocket.class);

                        if (testClassExists(JETTY_9))
                            add(Jetty9AsyncSupportWithWebSocket.class);

                        if (testClassExists(JETTY_8))
                            add(JettyAsyncSupportWithWebSocket.class);

                        if (testClassExists(GRIZZLY_WEBSOCKET))
                            add(GlassFishWebSocketSupport.class);

                        if (testClassExists(GRIZZLY2_WEBSOCKET))
                            add(Grizzly2WebSocketSupport.class);

                        if (testClassExists(JBOSS_AS7_WEBSOCKET))
                            add(JBossAsyncSupportWithWebSocket.class);
                    }

                }
            }
        };
    }

    /**
     * This method is used to determine the default AsyncSupport if all else fails.
     *
     * @param preferBlocking
     * @return
     */
    public AsyncSupport defaultCometSupport(final boolean preferBlocking) {
        if (!preferBlocking && testClassExists(SERVLET_30)) {
            return new Servlet30CometSupport(config);
        } else {
            return new BlockingIOCometSupport(config);
        }
    }

    /**
     * Given a Class of something that extends AsyncSupport, it tries to return an instance of that class.
     * <p/>
     * The class has to have a visible constructor with the signature (@link {AtmosphereConfig}).
     *
     * @param targetClass
     * @return an instance of the specified class or null if the class cannot be instantiated
     */
    public AsyncSupport newCometSupport(final Class<? extends AsyncSupport> targetClass) {
        try {
            return (AsyncSupport) targetClass.getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                    .newInstance(config);
        } catch (final Exception e) {
            logger.warn("Failed to create AsyncSupport class: {}, error: {}", targetClass, e);

            Throwable cause = e.getCause();
            if (cause != null) {
                logger.error("Real error: {}", cause.getMessage(), cause);
            }
            return null;
        }
    }

    public AsyncSupport newCometSupport(final String targetClassFQN) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return (AsyncSupport) cl.loadClass(targetClassFQN)
                    .getDeclaredConstructor(new Class[]{AtmosphereConfig.class}).newInstance(config);
        } catch (final Exception e) {
            logger.error("Failed to create AsyncSupport class: {}, error: {}", targetClassFQN, e);
            Throwable cause = e.getCause();
            if (cause != null) {
                logger.error("Real error: {}", cause.getMessage(), cause);
            }
            throw new IllegalArgumentException("Unable to create " + targetClassFQN, e);
        }
    }

    /**
     * This method is the general interface to the outside world.
     *
     * @param useNativeIfPossible - should the resolver try to use a native container comet support if present?
     * @param defaultToBlocking   - should the resolver default to blocking IO comet support?
     * @return an instance of AsyncSupport
     */
    public AsyncSupport resolve(final boolean useNativeIfPossible, final boolean defaultToBlocking) {
        final AsyncSupport servletAsyncSupport = defaultCometSupport(defaultToBlocking);

        final AsyncSupport nativeSupport;
        if (!defaultToBlocking && (useNativeIfPossible ||
                servletAsyncSupport.getClass().getName().equals(BlockingIOCometSupport.class.getName()))) {
            nativeSupport = resolveNativeCometSupport(detectContainersPresent());
            return nativeSupport == null ? servletAsyncSupport : nativeSupport;
        }
        return servletAsyncSupport;
    }

    @Override
    public AsyncSupport resolve(boolean useNativeIfPossible, boolean defaultToBlocking, boolean useServlet30Async) {
        AsyncSupport cs = null;

        // Validate the value for old Servlet Container.
        useServlet30Async = testClassExists(SERVLET_30);

        if (!defaultToBlocking) {
            List<Class<? extends AsyncSupport>> l = detectWebSocketPresent(useNativeIfPossible, useServlet30Async);

            if (!l.isEmpty()) {
                cs = resolveWebSocket(l);
            }
        }

        if (cs == null) {
            AsyncSupport nativeSupport = resolveNativeCometSupport(detectContainersPresent());
            return nativeSupport == null ? defaultCometSupport(defaultToBlocking) : nativeSupport;
        } else {
            return cs;
        }
    }

    public AsyncSupport resolveWebSocket(final java.util.List<Class<? extends AsyncSupport>> available) {
        if (available == null || available.isEmpty()) return null;
        else return newCometSupport(available.get(0));
    }

    /**
     * This method is called to determine which native comet support to the used.
     *
     * @param available
     * @return the result of @link {resolveMultipleNativeSupportConflict} if there are more than 1 item in the list of available ontainers
     */
    protected AsyncSupport resolveNativeCometSupport(final java.util.List<Class<? extends AsyncSupport>> available) {
        if (available == null || available.isEmpty()) return null;
        else if (available.size() == 1) return newCometSupport(available.get(0));
        else return resolveMultipleNativeSupportConflict(available);
    }

    /**
     * This method is called if there are more than one potential native container in scope.
     *
     * @return a AsyncSupport instance
     */
    protected AsyncSupport resolveMultipleNativeSupportConflict(final List<Class<? extends AsyncSupport>> available) {
        final StringBuilder b = new StringBuilder("Found multiple containers, please specify which one to use: ");
        for (Class<? extends AsyncSupport> cs : available) {
            b.append((cs != null) ? cs.getCanonicalName() : "null").append(", ");
        }

        b.append(" until you do, Atmosphere will use:" + available.get(0));
        logger.warn("{}", b.toString());
        return newCometSupport(available.get(0));
    }
}