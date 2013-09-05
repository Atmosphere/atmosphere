/*
 * Copyright 2012 Jeanfrancois Arcand
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
import org.atmosphere.config.managed.AnnotationServiceInterceptor;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import static org.atmosphere.annotation.AnnotationUtil.*;

@AtmosphereAnnotation(ManagedService.class)
public class ManagedServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(ManagedServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<? extends Annotation> annotation, Class<?> discoveredClass) {
        try {
            Class<?> aClass = discoveredClass;
            ManagedService a = aClass.getAnnotation(ManagedService.class);
            List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();

            atmosphereConfig(a.atmosphereConfig(), framework);
            framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
            filters(a.broadcastFilters(), framework);

            final Class<? extends AtmosphereResourceEventListener>[] listeners = a.listeners();
            if (listeners.length > 0) {
                try {
                    AtmosphereInterceptor ai = new AtmosphereInterceptor() {

                        @Override
                        public void configure(AtmosphereConfig config) {
                        }

                        @Override
                        public Action inspect(AtmosphereResource r) {
                            for (Class<? extends AtmosphereResourceEventListener> l : listeners) {
                                try {
                                    r.addEventListener(l.newInstance());
                                } catch (Throwable e) {
                                    logger.warn("", e);
                                }
                            }
                            return Action.CONTINUE;
                        }

                        @Override
                        public void postInspect(AtmosphereResource r) {
                        }

                        @Override
                        public String toString() {
                            return "@ManagedService Event Listeners";
                        }

                    };
                    l.add(ai);
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }

            Object c = aClass.newInstance();
            AtmosphereHandler handler = new ManagedAtmosphereHandler(c);
            Class<?>[] interceptors = a.interceptors();
            for (Class i : interceptors) {
                try {
                    AtmosphereInterceptor ai;
                    if (AnnotationServiceInterceptor.class.isAssignableFrom(i)) {
                        ai = new AnnotationServiceInterceptor(ManagedAtmosphereHandler.class.cast(handler));
                    } else {
                        ai = (AtmosphereInterceptor) i.newInstance();
                    }
                    l.add(ai);
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }
            framework.addAtmosphereHandler(a.path(), handler, l);
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
