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
import org.atmosphere.cache.DefaultBroadcasterCache;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.atmosphere.cpr.FrameworkConfig.*;

/**
 * Encapsulates broadcaster-related configuration and state for the Atmosphere framework.
 * Manages the broadcaster factory, cache, filters, inspectors, listeners, and related factories.
 */
public class BroadcasterSetup {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterSetup.class);

    final List<String> broadcasterFilters = new ArrayList<>();
    final ConcurrentLinkedQueue<String> broadcasterTypes = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors = new ConcurrentLinkedQueue<>();
    final List<BroadcasterListener> broadcasterListeners = new CopyOnWriteArrayList<>();
    final LinkedList<BroadcasterCacheListener> broadcasterCacheListeners = new LinkedList<>();
    final List<BroadcasterConfig.FilterManipulator> filterManipulators = new ArrayList<>();
    final ReentrantLock resourceFactoryLock = new ReentrantLock();
    final ReentrantLock sessionFactoryLock = new ReentrantLock();

    String broadcasterClassName = DefaultBroadcaster.class.getName();
    boolean isBroadcasterSpecified;
    BroadcasterFactory broadcasterFactory;
    String broadcasterFactoryClassName;
    String broadcasterCacheClassName;
    String broadcasterLifeCyclePolicy = "NEVER";
    AtmosphereResourceFactory arFactory;
    MetaBroadcaster metaBroadcaster;
    AtmosphereResourceSessionFactory sessionFactory;
    String defaultSerializerClassName;
    Class<Serializer> defaultSerializerClass;

    private final AtmosphereConfig config;
    private Supplier<Map<String, AtmosphereHandlerWrapper>> handlersSupplier;

    BroadcasterSetup(AtmosphereConfig config) {
        this.config = config;
    }

    void setHandlersSupplier(Supplier<Map<String, AtmosphereHandlerWrapper>> supplier) {
        this.handlersSupplier = supplier;
    }

    /**
     * The order of addition is quite important here.
     */
    void populateBroadcasterType() {
        broadcasterTypes.add(KAFKA_BROADCASTER);
        broadcasterTypes.add(HAZELCAST_BROADCASTER);
        broadcasterTypes.add(XMPP_BROADCASTER);
        broadcasterTypes.add(REDIS_BROADCASTER);
        broadcasterTypes.add(JGROUPS_BROADCASTER);
        broadcasterTypes.add(JMS_BROADCASTER);
        broadcasterTypes.add(RMI_BROADCASTER);
        broadcasterTypes.add(RABBITMQ_BROADCASTER);
    }

    @SuppressWarnings("unchecked")
    void configureBroadcasterFactory() {
        var fwk = config.framework();
        try {
            if (!isBroadcasterSpecified) {
                broadcasterClassName = lookupDefaultBroadcasterType(broadcasterClassName);
            }

            if (broadcasterFactoryClassName != null && broadcasterFactory == null) {
                broadcasterFactory = fwk.newClassInstance(BroadcasterFactory.class,
                        (Class<BroadcasterFactory>) IOUtils.loadClass(fwk.getClass(), broadcasterFactoryClassName));
                Class<? extends Broadcaster> bc =
                        (Class<? extends Broadcaster>) IOUtils.loadClass(fwk.getClass(), broadcasterClassName);
                broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
            }

            if (broadcasterFactory == null) {
                Class<? extends Broadcaster> bc =
                        (Class<? extends Broadcaster>) IOUtils.loadClass(fwk.getClass(), broadcasterClassName);
                broadcasterFactory = fwk.newClassInstance(BroadcasterFactory.class, DefaultBroadcasterFactory.class);
                broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
            }

            for (BroadcasterListener b : broadcasterListeners) {
                broadcasterFactory.addBroadcasterListener(b);
            }
        } catch (Exception ex) {
            logger.error("Unable to configure Broadcaster/Factory/Cache", ex);
        }
    }

    @SuppressWarnings("unchecked")
    void configureBroadcaster() {
        var fwk = config.framework();
        try {
            var i = handlersSupplier.get().entrySet().iterator();
            AtmosphereHandlerWrapper w;
            Map.Entry<String, AtmosphereHandlerWrapper> e;
            while (i.hasNext()) {
                e = i.next();
                w = e.getValue();

                if (w.broadcaster == null) {
                    w.broadcaster = broadcasterFactory.get(w.mapping);
                } else {
                    if (broadcasterCacheClassName != null
                            && w.broadcaster.getBroadcasterConfig().getBroadcasterCache().getClass().getName().equals(
                            DefaultBroadcasterCache.class.getName())) {
                        BroadcasterCache cache = fwk.newClassInstance(BroadcasterCache.class,
                                (Class<BroadcasterCache>) IOUtils.loadClass(fwk.getClass(), broadcasterCacheClassName));
                        cache.configure(config);
                        w.broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to configure Broadcaster/Factory/Cache", ex);
        }
    }

    String lookupDefaultBroadcasterType(String defaultB) {
        if (autodetectBroadcaster()) {
            for (String b : broadcasterTypes) {
                try {
                    Class.forName(b);
                    logger.info("Detected a Broadcaster {} on the classpath. " +
                            "This broadcaster will be used by default and will override any annotated resources. " +
                            "Set {} to false to change the behavior", b, ApplicationConfig.AUTODETECT_BROADCASTER);
                    isBroadcasterSpecified = true;
                    return b;
                } catch (ClassNotFoundException e) {
                }
            }
        }
        return defaultB;
    }

    boolean autodetectBroadcaster() {
        var servletConfig = config.getServletConfig();
        if (servletConfig == null) {
            return true;
        }
        String autodetect = servletConfig.getInitParameter(ApplicationConfig.AUTODETECT_BROADCASTER);
        return autodetect == null || Boolean.parseBoolean(autodetect);
    }

    void configureMetaBroadcaster() {
        var fwk = config.framework();
        try {
            metaBroadcaster = fwk.newClassInstance(MetaBroadcaster.class, DefaultMetaBroadcaster.class);
            metaBroadcaster.configure(config);
        } catch (InstantiationException | IllegalAccessException e) {
            logger.error("", e);
        }
    }

    void configureAtmosphereResourceFactory() {
        if (arFactory != null) return;

        resourceFactoryLock.lock();
        try {
            if (arFactory != null) return;
            try {
                arFactory = config.framework().newClassInstance(AtmosphereResourceFactory.class, DefaultAtmosphereResourceFactory.class);
            } catch (InstantiationException | IllegalAccessException e) {
                logger.error("", e);
            }
            arFactory.configure(config);
        } finally {
            resourceFactoryLock.unlock();
        }
    }

    void initDefaultSerializer() {
        if (defaultSerializerClassName != null && !defaultSerializerClassName.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Class<Serializer> clazz = (Class<Serializer>) IOUtils.loadClass(Serializer.class, defaultSerializerClassName);
                if (Serializer.class.isAssignableFrom(clazz)) {
                    defaultSerializerClass = clazz;
                } else {
                    logger.error("Default Serializer class name does not implement Serializer interface");
                    defaultSerializerClassName = null;
                    defaultSerializerClass = null;
                }
            } catch (Exception e) {
                logger.error("Unable to set default Serializer", e);
                defaultSerializerClassName = null;
                defaultSerializerClass = null;
            }
        } else {
            defaultSerializerClassName = null;
            defaultSerializerClass = null;
        }
    }

    AtmosphereResourceSessionFactory sessionFactory() {
        if (sessionFactory != null) return sessionFactory;

        sessionFactoryLock.lock();
        try {
            if (sessionFactory == null) {
                try {
                    sessionFactory = config.framework().newClassInstance(AtmosphereResourceSessionFactory.class, DefaultAtmosphereResourceSessionFactory.class);
                } catch (InstantiationException | IllegalAccessException e) {
                    logger.error("", e);
                }
            }
        } finally {
            sessionFactoryLock.unlock();
        }
        return sessionFactory;
    }

    AtmosphereResourceFactory atmosphereFactory() {
        if (arFactory == null) {
            configureAtmosphereResourceFactory();
        }
        return arFactory;
    }

    /**
     * Reset all state. Called during framework destroy/reset.
     */
    void clear() {
        broadcasterFilters.clear();
        broadcasterTypes.clear();
        inspectors.clear();
        broadcasterListeners.clear();
        broadcasterCacheListeners.clear();
        filterManipulators.clear();
        broadcasterFactory = null;
        arFactory = null;
        metaBroadcaster = null;
        sessionFactory = null;
    }
}
