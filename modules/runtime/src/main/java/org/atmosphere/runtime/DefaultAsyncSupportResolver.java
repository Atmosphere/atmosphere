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
import org.atmosphere.container.NettyCometSupport;
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
    public final static String NETTY = "org.jboss.netty.channel.Channel";
    public final static String JSR356_WEBSOCKET = "javax.websocket.Endpoint";

    private final AtmosphereConfig config;

    public DefaultAsyncSupportResolver(final AtmosphereConfig config) {
        this.config = config;
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
                if (testClassExists(NETTY))
                    add(NettyCometSupport.class);
            }
        };
    }

    public List<Class<? extends AsyncSupport>> detectWebSocketPresent(final boolean useNativeIfPossible, final boolean useServlet30Async) {

        return new LinkedList<Class<? extends AsyncSupport>>() {
            {
                add(JSR356AsyncSupport.class);
            }
        };
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
            return resolveNativeCometSupport(detectContainersPresent());
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