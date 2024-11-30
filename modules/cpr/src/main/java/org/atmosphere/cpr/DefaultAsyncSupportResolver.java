/*
 * Copyright 2008-2024 Async-IO.org
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
import org.atmosphere.container.JSR356AsyncSupport;
import org.atmosphere.container.NettyCometSupport;
import org.atmosphere.container.Servlet30CometSupport;
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

    private static Logger logger = LoggerFactory.getLogger(DefaultAsyncSupportResolver.class);

    private static String SERVLET_30 = "jakarta.servlet.AsyncListener";
    private static String NETTY = "org.jboss.netty.channel.Channel";
    private static String JSR356_WEBSOCKET = "jakarta.websocket.Endpoint";

    private AtmosphereConfig config;
    private boolean suppress356;

    public DefaultAsyncSupportResolver(final AtmosphereConfig config) {
        this.config = config;
        this.suppress356 = Boolean.parseBoolean(config.getInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356));
    }

    // Getter and Setter Methods for the variables
    public static String getServlet30() {
        return SERVLET_30;
    }

    public static void setServlet30(String servlet30) {
        SERVLET_30 = servlet30;
    }

    public static String getNetty() {
        return NETTY;
    }

    public static void setNetty(String netty) {
        NETTY = netty;
    }

    public static String getJsr356WebSocket() {
        return JSR356_WEBSOCKET;
    }

    public static void setJsr356WebSocket(String jsr356WebSocket) {
        JSR356_WEBSOCKET = jsr356WebSocket;
    }

    public AtmosphereConfig getConfig() {
        return config;
    }

    public void setConfig(AtmosphereConfig config) {
        this.config = config;
    }

    public boolean isSuppress356() {
        return suppress356;
    }

    public void setSuppress356(boolean suppress356) {
        this.suppress356 = suppress356;
    }

    // Getter and Setter for logger
    public static Logger getLogger() {
        return logger;
    }

    public static void setLogger(Logger logger) {
        DefaultAsyncSupportResolver.logger = logger;
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
            getLogger().debug(exists ? "Found {}" : "Not found {}", testClass);
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
                if (testClassExists(getNetty())) {
                    add(NettyCometSupport.class);
                }
            }
        };
    }

    public List<Class<? extends AsyncSupport>> detectWebSocketPresent(final boolean useNativeIfPossible, final boolean useServlet30Async) {
        return new LinkedList<Class<? extends AsyncSupport>>() {
            {
                if (useServlet30Async && !useNativeIfPossible) {
                    if (!isSuppress356() && testClassExists(getJsr356WebSocket())) {
                        add(JSR356AsyncSupport.class);
                    }
                } else {
                    if (!isSuppress356() && testClassExists(getJsr356WebSocket())) {
                        add(JSR356AsyncSupport.class);
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
        if (!preferBlocking && testClassExists(getServlet30())) {
            return new Servlet30CometSupport(getConfig());
        } else {
            return new BlockingIOCometSupport(getConfig());
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
            return targetClass.getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                    .newInstance(getConfig());
        } catch (final Exception e) {
            getLogger().warn("Failed to create AsyncSupport class: {}, error: {}", targetClass, e);

            Throwable cause = e.getCause();
            if (cause != null) {
                getLogger().error("Real error: {}", cause.getMessage(), cause);
            }
            return null;
        }
    }

    public AsyncSupport newCometSupport(final String targetClassFQN) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return (AsyncSupport) cl.loadClass(targetClassFQN)
                    .getDeclaredConstructor(new Class[]{AtmosphereConfig.class}).newInstance(getConfig());
        } catch (final Exception e) {
            getLogger().error("Failed to create AsyncSupport class: {}, error: {}", targetClassFQN, e);
            Throwable cause = e.getCause();
            if (cause != null) {
                getLogger().error("Real error: {}", cause.getMessage(), cause);
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
        useServlet30Async = testClassExists(getServlet30());

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
     * @return the result of @link {resolveMultipleNativeSupportConflict} if there are more than 1 item in the list of available containers
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

        b.append(" until you do, Atmosphere will use:").append(available.get(0));
        getLogger().warn("{}", b.toString());
        return newCometSupport(available.get(0));
    }
}

