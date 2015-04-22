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
package org.atmosphere.annotation;

import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.config.managed.ManagedServiceInterceptor;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.SuspendTrackerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

public class AnnotationUtil {

    public static final Logger logger = LoggerFactory.getLogger(AnnotationUtil.class);

    private static final List<Class<? extends AtmosphereInterceptor>> MANAGED_ATMOSPHERE_INTERCEPTORS = new LinkedList<Class<? extends AtmosphereInterceptor>>() {
        {
            add(AtmosphereResourceLifecycleInterceptor.class);
            add(TrackMessageSizeInterceptor.class);
            add(SuspendTrackerInterceptor.class);
            add(ManagedServiceInterceptor.class);
        }
    };

    public static void interceptors(Class<? extends AtmosphereInterceptor>[] interceptors, AtmosphereFramework framework) {
        for (Class i : interceptors) {
            try {
                framework.interceptor(framework.newClassInstance(AtmosphereInterceptor.class, i));
            } catch (Throwable e) {
                logger.warn("", e);
            }
        }
    }

    public static void filters(Class<? extends BroadcastFilter>[] bf, AtmosphereFramework framework) throws IllegalAccessException, InstantiationException {
        for (Class<? extends BroadcastFilter> b : bf) {
            framework.broadcasterFilters(framework.newClassInstance(BroadcastFilter.class, b));
        }
    }

    public static void atmosphereConfig(String[] m, AtmosphereFramework framework) {
        for (String s : m) {
            String[] nv = s.split("=");
            framework.addInitParameter(nv[0], nv[1]);
            framework.reconfigureInitParams(true);
        }
    }

    public static void defaultManagedServiceInterceptors(AtmosphereFramework framework, List<AtmosphereInterceptor> l) {
        interceptorsForManagedService(framework, MANAGED_ATMOSPHERE_INTERCEPTORS, l, false);
    }

    public static void interceptorsForManagedService(AtmosphereFramework framework, List<Class<? extends AtmosphereInterceptor>> interceptors, List<AtmosphereInterceptor> l) {
        interceptorsForManagedService(framework, interceptors, l, true);
    }

    public static void interceptorsForManagedService(AtmosphereFramework framework, List<Class<? extends AtmosphereInterceptor>> interceptors, List<AtmosphereInterceptor> l, boolean checkDuplicate) {
        for (Class<? extends AtmosphereInterceptor> i : interceptors) {
            if (!framework.excludedInterceptors().contains(i.getName())
                    && (!checkDuplicate || checkDefault(i))) {
                try {
                    logger.info("Adding {}", i);
                    l.add(framework.newClassInstance(AtmosphereInterceptor.class, i));
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }
        }
    }

    public static void interceptorsForHandler(AtmosphereFramework framework, List<Class<? extends AtmosphereInterceptor>> interceptors, List<AtmosphereInterceptor> l) {
        for (Class<? extends AtmosphereInterceptor> i : interceptors) {
            if (!framework.excludedInterceptors().contains(i.getName())
                    && (!AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(i))) {
                try {
                    logger.info("Adding {}", i);
                    l.add(framework.newClassInstance(AtmosphereInterceptor.class, i));
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }
        }
    }

    public static boolean checkDefault(Class<? extends AtmosphereInterceptor> i) {
        return !MANAGED_ATMOSPHERE_INTERCEPTORS.contains(i) && !AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS.contains(i);
    }



    public static AtmosphereInterceptor listeners(final Class<? extends AtmosphereResourceEventListener>[] listeners, final AtmosphereFramework framework) {
        if (listeners.length > 0) {
            try {
                return new AtmosphereInterceptorAdapter() {

                    @Override
                    public Action inspect(AtmosphereResource r) {
                        if (!r.isSuspended()) {
                            for (Class<? extends AtmosphereResourceEventListener> l : listeners) {
                                try {
                                    r.addEventListener(framework.newClassInstance(AtmosphereResourceEventListener.class, l));
                                } catch (Throwable e) {
                                    logger.warn("", e);
                                }
                            }
                        }
                        return Action.CONTINUE;
                    }

                    @Override
                    public String toString() {
                        return "@Service Event Listeners";
                    }

                };
            } catch (Throwable e) {
                logger.warn("", e);
            }
        }
        return null;
    }

    public static Broadcaster broadcaster(AtmosphereFramework framework, Class<? extends Broadcaster> broadcaster, String path) throws Exception {
        return framework.getBroadcasterFactory().lookup(broadcasterClass(framework, broadcaster), path, true);
    }

    public static Class<? extends Broadcaster> broadcasterClass(AtmosphereFramework framework, Class<? extends Broadcaster> broadcaster) throws Exception {
        if (framework.isBroadcasterSpecified()) {
            try {
                broadcaster = (Class<? extends Broadcaster>) framework.getClass().getClassLoader().loadClass(framework.getDefaultBroadcasterClassName());
            } catch (ClassNotFoundException ex) {
                broadcaster = (Class<? extends Broadcaster>) Thread.currentThread().getContextClassLoader().loadClass(framework.getDefaultBroadcasterClassName());
            }
        }
        return broadcaster;
    }
}
