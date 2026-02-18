/*
 * Copyright 2008-2026 Async-IO.org
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
import org.atmosphere.container.Servlet30CometSupport;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * This is the default implementation of @link {AsyncSupportResolver}.
 *
 * @author Viktor Klang
 */
public class DefaultAsyncSupportResolver implements AsyncSupportResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAsyncSupportResolver.class);

    public final static String SERVLET_30 = "jakarta.servlet.AsyncListener";
    public final static String JSR356_WEBSOCKET = "jakarta.websocket.Endpoint";

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
    public List<Class<? extends AsyncSupport<?>>> detectContainersPresent() {
        return new ArrayList<>();
    }

    public List<Class<? extends AsyncSupport<?>>> detectWebSocketPresent(final boolean useNativeIfPossible, final boolean useServlet30Async) {

        List<Class<? extends AsyncSupport<?>>> result = new ArrayList<>();
        if (useServlet30Async && !useNativeIfPossible) {
            if (!suppress356 && testClassExists(JSR356_WEBSOCKET)) {
                result.add(JSR356AsyncSupport.class);
            }
        } else {
            if (!suppress356 && testClassExists(JSR356_WEBSOCKET)) {
                result.add(JSR356AsyncSupport.class);
            }
        }
        return result;
    }

    /**
     * This method is used to determine the default AsyncSupport if all else fails.
     *
     * @param preferBlocking
     * @return
     */
    public AsyncSupport<?> defaultCometSupport(final boolean preferBlocking) {
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
    public AsyncSupport<?> newCometSupport(final Class<? extends AsyncSupport<?>> targetClass) {
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

    public AsyncSupport<?> newCometSupport(final String targetClassFQN) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return (AsyncSupport<?>) cl.loadClass(targetClassFQN)
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
    public AsyncSupport<?> resolve(final boolean useNativeIfPossible, final boolean defaultToBlocking) {
        final AsyncSupport<?> servletAsyncSupport = defaultCometSupport(defaultToBlocking);

        final AsyncSupport<?> nativeSupport;
        if (!defaultToBlocking && (useNativeIfPossible ||
                servletAsyncSupport.getClass().getName().equals(BlockingIOCometSupport.class.getName()))) {
            nativeSupport = resolveNativeCometSupport(detectContainersPresent());
            return nativeSupport == null ? servletAsyncSupport : nativeSupport;
        }
        return servletAsyncSupport;
    }

    @Override
    public AsyncSupport<?> resolve(boolean useNativeIfPossible, boolean defaultToBlocking, boolean useServlet30Async) {
        AsyncSupport<?> cs = null;

        // Validate the value for old Servlet Container.
        useServlet30Async = testClassExists(SERVLET_30);

        if (!defaultToBlocking) {
            List<Class<? extends AsyncSupport<?>>> l = detectWebSocketPresent(useNativeIfPossible, useServlet30Async);

            if (!l.isEmpty()) {
                cs = resolveWebSocket(l);
            }
        }

        if (cs == null) {
            AsyncSupport<?> nativeSupport = resolveNativeCometSupport(detectContainersPresent());
            return nativeSupport == null ? defaultCometSupport(defaultToBlocking) : nativeSupport;
        } else {
            return cs;
        }
    }

    public AsyncSupport<?> resolveWebSocket(final java.util.List<Class<? extends AsyncSupport<?>>> available) {
        if (available == null || available.isEmpty()) return null;
        else return newCometSupport(available.get(0));
    }

    /**
     * This method is called to determine which native comet support to the used.
     *
     * @param available
     * @return the result of @link {resolveMultipleNativeSupportConflict} if there are more than 1 item in the list of available ontainers
     */
    protected AsyncSupport<?> resolveNativeCometSupport(final java.util.List<Class<? extends AsyncSupport<?>>> available) {
        if (available == null || available.isEmpty()) return null;
        else if (available.size() == 1) return newCometSupport(available.get(0));
        else return resolveMultipleNativeSupportConflict(available);
    }

    /**
     * This method is called if there are more than one potential native container in scope.
     *
     * @return a AsyncSupport instance
     */
    protected AsyncSupport<?> resolveMultipleNativeSupportConflict(final List<Class<? extends AsyncSupport<?>>> available) {
        final StringBuilder b = new StringBuilder("Found multiple containers, please specify which one to use: ");
        for (Class<? extends AsyncSupport<?>> cs : available) {
            b.append((cs != null) ? cs.getCanonicalName() : "null").append(", ");
        }

        b.append(" until you do, Atmosphere will use:").append(available.get(0));
        logger.warn("{}", b.toString());
        return newCometSupport(available.get(0));
    }
}
