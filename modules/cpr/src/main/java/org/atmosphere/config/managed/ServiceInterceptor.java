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
package org.atmosphere.config.managed;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

public abstract class ServiceInterceptor extends AtmosphereInterceptorAdapter {
    private final Logger logger = LoggerFactory.getLogger(ServiceInterceptor.class);

    protected AtmosphereConfig config;

    public ServiceInterceptor() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        AtmosphereFramework.AtmosphereHandlerWrapper w = null;
        try {
            w = (AtmosphereFramework.AtmosphereHandlerWrapper)
                                        r.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER);

            if (!w.wildcardMapping()) return Action.CONTINUE;

            mapAnnotatedService(r.getRequest(), w);

            return Action.CONTINUE;
        } finally {
            if (w != null && w.needRequestScopedInjection()) {
                try {
                    Utils.inject(r);
                } catch (IllegalAccessException e) {
                    logger.error("", e);
                }
            }
        }
    }

    /**
     * Inspect the request and its mapped {@link org.atmosphere.cpr.AtmosphereHandler} to determine if the '{}' was used when defined the
     * annotation's path value. It will create a new {@link org.atmosphere.cpr.AtmosphereHandler} in case {} is detected .
     *
     * @param request
     * @param w
     * @return
     */
    protected void mapAnnotatedService(AtmosphereRequest request, AtmosphereFramework.AtmosphereHandlerWrapper w) {
        Broadcaster b = w.broadcaster;

        String path;
        String pathInfo = null;
        boolean reMap = false;

        path = Utils.pathInfo(request);

        // Remove the Broadcaster with curly braces
        if (b.getID().contains("{")) {
            reMap = true;
            config.getBroadcasterFactory().remove(b.getID());
        }

        request.localAttributes().put(Named.class.getName(), path);

        mapAnnotatedService(reMap, path, request, w);
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.AFTER_DEFAULT;
    }

    protected abstract void mapAnnotatedService(boolean reMap, String path, AtmosphereRequest request, AtmosphereFramework.AtmosphereHandlerWrapper w);
}
