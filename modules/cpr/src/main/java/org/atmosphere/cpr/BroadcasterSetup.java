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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Encapsulates broadcaster-related configuration and state for the Atmosphere framework.
 * Manages the broadcaster factory, cache, filters, inspectors, listeners, and related factories.
 */
public class BroadcasterSetup {

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

    BroadcasterSetup(AtmosphereConfig config) {
        this.config = config;
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
