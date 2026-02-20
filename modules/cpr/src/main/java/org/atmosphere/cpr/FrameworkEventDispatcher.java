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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages listener registration and event dispatch for the Atmosphere framework.
 * Handles {@link AsyncSupportListener}, {@link AtmosphereResourceListener}, and
 * {@link AtmosphereFrameworkListener} lifecycle events.
 */
public class FrameworkEventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkEventDispatcher.class);

    private final List<AsyncSupportListener> asyncSupportListeners = new ArrayList<>();
    private final List<AtmosphereResourceListener> atmosphereResourceListeners = new ArrayList<>();
    private final List<AtmosphereFrameworkListener> frameworkListeners = new LinkedList<>();

    public FrameworkEventDispatcher() {
    }

    /**
     * Add an {@link AsyncSupportListener}.
     *
     * @param listener an {@link AsyncSupportListener}
     */
    public void addAsyncSupportListener(AsyncSupportListener listener) {
        asyncSupportListeners.add(listener);
    }

    /**
     * Return the list of {@link AsyncSupportListener}s.
     *
     * @return the list of {@link AsyncSupportListener}s
     */
    public List<AsyncSupportListener> asyncSupportListeners() {
        return asyncSupportListeners;
    }

    /**
     * Add a {@link AtmosphereResourceListener}.
     *
     * @param listener a {@link AtmosphereResourceListener}
     */
    public void addAtmosphereResourceListener(AtmosphereResourceListener listener) {
        atmosphereResourceListeners.add(listener);
    }

    /**
     * Return the list of {@link AtmosphereResourceListener}s.
     *
     * @return the list of {@link AtmosphereResourceListener}s
     */
    public List<AtmosphereResourceListener> atmosphereResourceListeners() {
        return atmosphereResourceListeners;
    }

    /**
     * Add a {@link AtmosphereFrameworkListener}.
     *
     * @param listener {@link AtmosphereFrameworkListener}
     */
    public void addFrameworkListener(AtmosphereFrameworkListener listener) {
        frameworkListeners.add(listener);
    }

    /**
     * Return the list of {@link AtmosphereFrameworkListener}s.
     *
     * @return the list of {@link AtmosphereFrameworkListener}s
     */
    public List<AtmosphereFrameworkListener> frameworkListeners() {
        return frameworkListeners;
    }

    /**
     * Notify all {@link AsyncSupportListener}s of an action.
     */
    public void notify(Action.TYPE type, AtmosphereRequest request, AtmosphereResponse response) {
        for (AsyncSupportListener l : asyncSupportListeners) {
            try {
                switch (type) {
                    case TIMEOUT -> l.onTimeout(request, response);
                    case CANCELLED -> l.onClose(request, response);
                    case SUSPEND -> l.onSuspend(request, response);
                    case RESUME -> l.onResume(request, response);
                    case DESTROYED -> l.onDestroyed(request, response);
                    default -> {}
                }
            } catch (Throwable t) {
                logger.warn("", t);
            }
        }
    }

    /**
     * Notify all {@link AtmosphereResourceListener}s of a disconnection.
     */
    public void notifyDestroyed(String uuid) {
        atmosphereResourceListeners.forEach(l -> l.onDisconnect(uuid));
    }

    /**
     * Notify all {@link AtmosphereResourceListener}s of a suspension.
     */
    public void notifySuspended(String uuid) {
        atmosphereResourceListeners.forEach(l -> l.onSuspended(uuid));
    }

    /**
     * Dispatch pre-init event to all {@link AtmosphereFrameworkListener}s.
     */
    public void onPreInit(AtmosphereFramework framework) {
        forEachFrameworkListener(l -> l.onPreInit(framework));
    }

    /**
     * Dispatch post-init event to all {@link AtmosphereFrameworkListener}s.
     */
    public void onPostInit(AtmosphereFramework framework) {
        forEachFrameworkListener(l -> l.onPostInit(framework));
    }

    /**
     * Dispatch pre-destroy event to all {@link AtmosphereFrameworkListener}s.
     */
    public void onPreDestroy(AtmosphereFramework framework) {
        forEachFrameworkListener(l -> l.onPreDestroy(framework));
    }

    /**
     * Dispatch post-destroy event to all {@link AtmosphereFrameworkListener}s.
     */
    public void onPostDestroy(AtmosphereFramework framework) {
        forEachFrameworkListener(l -> l.onPostDestroy(framework));
    }

    private void forEachFrameworkListener(Consumer<AtmosphereFrameworkListener> action) {
        for (AtmosphereFrameworkListener l : frameworkListeners) {
            try {
                action.accept(l);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    /**
     * Clear all listeners. Used during framework reset.
     */
    public void clear() {
        asyncSupportListeners.clear();
        atmosphereResourceListeners.clear();
        frameworkListeners.clear();
    }
}
