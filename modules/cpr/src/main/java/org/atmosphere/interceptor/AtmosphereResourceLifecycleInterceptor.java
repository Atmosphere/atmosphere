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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERERESOURCE_INTERCEPTOR_METHOD;

/**
 * <p>This {@link AtmosphereInterceptor} implementation automatically suspends the intercepted
 * {@link AtmosphereResource} and takes care of managing the response's state (flushing, resuming,
 * etc.) when a {@link org.atmosphere.cpr.Broadcaster#broadcast} is invoked. When used, {@link org.atmosphere.cpr.AtmosphereHandler} implementations no longer need to make calls to
 * {@link AtmosphereResource#suspend}.
 * </p>
 * If your application doesn't use {@link org.atmosphere.cpr.Broadcaster}, this interceptor will not work and you need to programmatically
 * resume, flush, etc.
 * <p/>
 * <p>By default, intercepted {@link AtmosphereResource} instances are suspended when a GET
 * request is received. You can change the triggering http method by configuring
 * {@link org.atmosphere.cpr.ApplicationConfig#ATMOSPHERERESOURCE_INTERCEPTOR_METHOD}
 * <p/>
 * <p/>
 * <p>Use this class when you don't want to manage the suspend/resume operation from your
 * particular Atmosphere framework implementation classes ({@link org.atmosphere.cpr.AtmosphereHandler},
 * {@link org.atmosphere.websocket.WebSocketHandler}, or
 * {@link org.atmosphere.cpr.Meteor} instances) or extensions (GWT, Jersey, Wicket, etc...)
 * </p>
 * <strong>For this mechanism to work properly, each client must set the
 * {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRANSPORT} header. Your AtmosphereHandler must also extends the
 * {@link org.atmosphere.handler.AbstractReflectorAtmosphereHandler} or implements the logic defined inside
 * {@link org.atmosphere.handler.AbstractReflectorAtmosphereHandler#postStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)} </strong>
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceLifecycleInterceptor implements AtmosphereInterceptor {

    private String method = "GET";
    private static final Logger logger = LoggerFactory.getLogger(AtmosphereResourceLifecycleInterceptor.class);

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_METHOD);
        if (s != null) {
            method = s;
        }
    }

    /**
     * Automatically suspend the {@link AtmosphereResource} based on {@link AtmosphereResource.TRANSPORT} value.
     *
     * @param r a {@link AtmosphereResource}
     * @return
     */
    @Override
    public Action inspect(AtmosphereResource r) {
        switch (r.transport()) {
            case JSONP:
            case AJAX:
            case LONG_POLLING:
                r.resumeOnBroadcast(true);
                break;
            default:
                break;
        }
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(final AtmosphereResource r) {

        if (r.transport().equals(AtmosphereResource.TRANSPORT.UNDEFINED)) return;

        if (!AtmosphereResourceImpl.class.cast(r).action().equals(Action.CANCELLED)
                && r.getRequest().getMethod().equalsIgnoreCase(method)) {
            logger.debug("Marking AtmosphereResource {} for suspend operation", r.uuid());
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onBroadcast(AtmosphereResourceEvent event) {
                    switch (r.transport()) {
                        case JSONP:
                        case AJAX:
                        case LONG_POLLING:
                            break;
                        default:
                            try {
                                r.getResponse().flushBuffer();
                            } catch (IOException e) {
                                logger.trace("", e);
                            }
                            break;
                    }
                }
            }).suspend();
        }
    }

    public String toString() {
        return "Atmosphere LifeCycle";
    }
}
