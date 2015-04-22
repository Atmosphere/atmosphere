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

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.BroadcasterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.broadcaster;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(ManagedService.class)
public class ManagedServiceProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(ManagedServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            Class<?> aClass = annotatedClass;
            ManagedService a = aClass.getAnnotation(ManagedService.class);
            framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());

            List<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, l);

            atmosphereConfig(a.atmosphereConfig(), framework);
            filters(a.broadcastFilters(), framework);

            AtmosphereInterceptor aa = listeners(a.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            Object c = framework.newClassInstance(Object.class, aClass);
            AtmosphereHandler handler = framework.newClassInstance(ManagedAtmosphereHandler.class,
                    ManagedAtmosphereHandler.class).configure(framework.getAtmosphereConfig(), c);

            framework.filterManipulator(new BroadcasterConfig.FilterManipulator() {
                @Override
                public Object unwrap(Object o) {
                    if (o != null && ManagedAtmosphereHandler.Managed.class.isAssignableFrom(o.getClass())) {
                        o = ManagedAtmosphereHandler.Managed.class.cast(o).object();
                    }
                    return o;
                }

                @Override
                public BroadcastFilter.BroadcastAction wrap(BroadcastFilter.BroadcastAction a, boolean wasWrapped) {
                    if (wasWrapped) {
                        return new BroadcastFilter.BroadcastAction(a.action(), new ManagedAtmosphereHandler.Managed(a.message()));
                    } else {
                        return a;
                    }
                }
            });

            AnnotationUtil.interceptorsForManagedService(framework, Arrays.asList(a.interceptors()), l);
            framework.addAtmosphereHandler(a.path(), handler, broadcaster(framework, a.broadcaster(), a.path()), l);
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
