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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.websocket.WebSocketFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * This enumeration represents all possible actions to specify in a meta service file.
 * </p>
 *
 * @author Guillaume DROUET
 * @version 1.0
 * @since 2.2.0
 */
public enum MetaServiceAction {

    /**
     * Install service.
     */
    INSTALL(new InstallMetaServiceProcedure()),

    /**
     * Exclude service.
     */
    EXCLUDE(new ExcludeMetaServiceProcedure());

    private static final Logger logger = LoggerFactory.getLogger(MetaServiceAction.class);

    /**
     * The procedure to apply.
     */
    private MetaServiceProcedure procedure;

    /**
     * <p>
     * Builds a new instance.
     * </p>
     *
     * @param p the enum procedure
     */
    MetaServiceAction(final MetaServiceProcedure p) {
        procedure = p;
    }

    /**
     * <p>
     * Applies this action to given class.
     * </p>
     *
     * @param fwk   the framework
     * @param clazz the class
     * @throws Exception if procedure fails
     */
    public void apply(final AtmosphereFramework fwk, final Class<?> clazz) throws Exception {
        procedure.apply(fwk, clazz);

    }

    /**
     * <p>
     * This interface defined a method with a signature like a procedure to process an action.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 2.2.0
     */
    private interface MetaServiceProcedure {

        /**
         * <p>
         * Processes an action.
         * </p>
         *
         * @param fwk   the framework
         * @param clazz the class to use during processing
         * @throws Exception if procedure fails
         */
        void apply(final AtmosphereFramework fwk, final Class<?> clazz) throws Exception;
    }

    /**
     * <p>
     * Install the classes.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 2.2.0
     */
    private static class InstallMetaServiceProcedure implements MetaServiceProcedure {

        /**
         * {@inheritDoc}
         */
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void apply(final AtmosphereFramework fwk, final Class<?> c) throws Exception {
            if (AtmosphereInterceptor.class.isAssignableFrom(c)) {
                fwk.interceptor(fwk.newClassInstance(AtmosphereInterceptor.class, (Class) c));
            } else if (Broadcaster.class.isAssignableFrom(c)) {
                fwk.setDefaultBroadcasterClassName(c.getName());
            } else if (BroadcasterListener.class.isAssignableFrom(c)) {
                fwk.addBroadcasterListener(fwk.newClassInstance(BroadcasterListener.class, (Class) c));
            } else if (BroadcasterCache.class.isAssignableFrom(c)) {
                fwk.setBroadcasterCacheClassName(c.getName());
            } else if (BroadcastFilter.class.isAssignableFrom(c)) {
                fwk.broadcasterFilters.add(c.getName());
            } else if (BroadcasterCacheInspector.class.isAssignableFrom(c)) {
                fwk.inspectors.add(fwk.newClassInstance(BroadcasterCacheInspector.class, (Class) c));
            } else if (AsyncSupportListener.class.isAssignableFrom(c)) {
                fwk.asyncSupportListeners.add(fwk.newClassInstance(AsyncSupportListener.class, (Class) c));
            } else if (AsyncSupport.class.isAssignableFrom(c)) {
                fwk.setAsyncSupport(fwk.newClassInstance(AsyncSupport.class, (Class) c));
            } else if (BroadcasterCacheListener.class.isAssignableFrom(c)) {
                fwk.broadcasterCacheListeners.add(fwk.newClassInstance(BroadcasterCacheListener.class, (Class) c));
            } else if (BroadcasterConfig.FilterManipulator.class.isAssignableFrom(c)) {
                fwk.filterManipulators.add(fwk.newClassInstance(BroadcasterConfig.FilterManipulator.class, (Class) c));
            } else if (WebSocketProtocol.class.isAssignableFrom(c)) {
                fwk.webSocketProtocolClassName = c.getName();
            } else if (WebSocketProcessor.class.isAssignableFrom(c)) {
                fwk.webSocketProcessorClassName = c.getName();
            } else if (AtmosphereResourceFactory.class.isAssignableFrom(c)) {
                fwk.setAndConfigureAtmosphereResourceFactory(fwk.newClassInstance(AtmosphereResourceFactory.class, (Class) c));
            } else if (AtmosphereFrameworkListener.class.isAssignableFrom(c)) {
                fwk.frameworkListener(fwk.newClassInstance(AtmosphereFrameworkListener.class, (Class) c));
            } else if (WebSocketFactory.class.isAssignableFrom(c)) {
                fwk.webSocketFactory(fwk.newClassInstance(WebSocketFactory.class, (Class) c));
            } else if (AtmosphereFramework.class.isAssignableFrom(c)) {
                // No OPS
            } else if (EndpointMapper.class.isAssignableFrom(c)) {
                fwk.endPointMapper(fwk.newClassInstance(EndpointMapper.class, (Class) c));
            } else {
                logger.warn("{} is not a framework service that could be installed", c.getName());
            }
        }
    }

    /**
     * <p>
     * Exclude the services.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 2.2.0
     */
    private static class ExcludeMetaServiceProcedure implements MetaServiceProcedure {

        /**
         * {@inheritDoc}
         */
        @Override
        public void apply(final AtmosphereFramework fwk, final Class<?> c) {
            if (AtmosphereInterceptor.class.isAssignableFrom(c)) {
                fwk.excludeInterceptor(c.getName());
            } else {
                logger.warn("{} is not a framework service that could be excluded, pull request is welcome ;-)", c.getName());
            }
        }
    }
}
