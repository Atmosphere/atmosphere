/*
 * Copyright 2014 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.FrameworkConfig.ASYNCHRONOUS_HOOK;

/**
 * An Interceptor that track idle {@link AtmosphereResource} and close it. This interceptor is useful for
 * tracking disconnected client that aren't detected by the network. A good example is a wireless connection
 * that goes down. In that case Tomcat and Jetty fail to detects the disconnect.
 *
 * @author Jeanfrancois Arcand
 */
public class IdleResourceInterceptor extends AtmosphereInterceptorAdapter {

    private final Logger logger = LoggerFactory.getLogger(IdleResourceInterceptor.class);
    private long maxInactiveTime = -1;
    private AtmosphereConfig config;
    private Future<?> future;

    public void configure(AtmosphereConfig config) {
        this.config = config;

        String maxInactive = config.getInitParameter(MAX_INACTIVE);
        if (maxInactive != null) {
            maxInactiveTime = Long.parseLong(maxInactive);
        }

        start();
    }

    private void start() {
        if (future != null) {
            future.cancel(false);
        }

        if (maxInactiveTime > 0) {
            logger.info("{} started with idle timeout set to {}", IdleResourceInterceptor.class.getSimpleName(), maxInactiveTime);
            future = ExecutorsFactory.getScheduler(config).scheduleAtFixedRate(new Runnable() {
                public void run() {
                    idleResources();
                }
            }, 0, 2, TimeUnit.SECONDS);
        }
    }

    protected void idleResources() {
        for (AtmosphereResource r : config.resourcesFactory().findAll()) {
            AtmosphereRequest req = AtmosphereResourceImpl.class.cast(r).getRequest(false);
            try {
                if (req.getAttribute(MAX_INACTIVE) == null) return;

                long l = (Long) req.getAttribute(MAX_INACTIVE);
                if (l > 0 && System.currentTimeMillis() - l > maxInactiveTime) {
                    try {
                        req.setAttribute(MAX_INACTIVE, (long) -1);

                        logger.debug("IdleResourceInterceptor disconnecting {}", r);

                        if (!AtmosphereResourceImpl.class.cast(r).isSuspended() || !AtmosphereResourceImpl.class.cast(r).isInScope()) {
                            return;
                        }

                        Future<?> f = (Future<?>) req.getAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE);
                        if (f != null) f.cancel(false);
                        req.removeAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE);

                        Object o = req.getAttribute(ASYNCHRONOUS_HOOK);
                        WebSocket webSocket = AtmosphereResourceImpl.class.cast(r).webSocket();

                        if (webSocket != null) {
                            webSocket.close();
                        } else {
                            req.setAttribute(ASYNCHRONOUS_HOOK, null);
                            AsynchronousProcessor.AsynchronousProcessorHook h;
                            if (o != null && AsynchronousProcessor.AsynchronousProcessorHook.class.isAssignableFrom(o.getClass())) {
                                h = (AsynchronousProcessor.AsynchronousProcessorHook) o;
                                h.closed();
                            }
                        }
                    } finally {
                        config.getBroadcasterFactory().removeAllAtmosphereResource(r);
                        config.resourcesFactory().unRegisterUuidForFindCandidate(r);
                    }
                }
            } catch (Throwable e) {
                logger.warn("IdleResourceInterceptor", e);
            }
        }
    }

    public long maxInactiveTime() {
        return maxInactiveTime;
    }

    public IdleResourceInterceptor maxInactiveTime(long maxInactiveTime) {
        this.maxInactiveTime = maxInactiveTime;
        start();
        return this;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        if (maxInactiveTime > 0) {
            AtmosphereResourceImpl.class.cast(r).getRequest(false).setAttribute(MAX_INACTIVE, System.currentTimeMillis());
        }
        return Action.CONTINUE;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

}

