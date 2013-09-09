/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.BroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnnotationUtil {

    public static final Logger logger = LoggerFactory.getLogger(AnnotationUtil.class);

    public static void interceptors(Class<? extends AtmosphereInterceptor>[] interceptors, AtmosphereFramework framework) {
        for (Class i : interceptors) {
            try {
                framework.interceptor((AtmosphereInterceptor) i.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        }
    }

    public static void filters(Class<? extends BroadcastFilter>[] bf, AtmosphereFramework framework) throws IllegalAccessException, InstantiationException {
        for (Class<? extends BroadcastFilter> b : bf) {
            framework.broadcasterFilters(b.newInstance());
        }
    }

    public static void atmosphereConfig(String[] m, AtmosphereFramework framework) {
        for (String s : m) {
            String[] nv = s.split("=");
            framework.addInitParameter(nv[0], nv[1]);
        }
    }

    public static AtmosphereInterceptor listeners(final Class<? extends AtmosphereResourceEventListener>[] listeners, AtmosphereFramework framework) {
        if (listeners.length > 0) {
            try {
                return new AtmosphereInterceptorAdapter() {

                    @Override
                    public Action inspect(AtmosphereResource r) {
                        if (!r.isSuspended()) {
                            for (Class<? extends AtmosphereResourceEventListener> l : listeners) {
                                try {
                                    r.addEventListener(l.newInstance());
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
}
