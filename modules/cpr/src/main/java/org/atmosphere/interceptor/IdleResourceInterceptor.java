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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

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
        if (logger.isTraceEnabled()) {
            logger.trace("{} monitoring {} AtmosphereResources", getClass().getSimpleName(), config.resourcesFactory().findAll().size());
        }

        for (AtmosphereResource r : config.resourcesFactory().findAll()) {

            if (Utils.pollableTransport(r.transport())) {
                continue;
            }

            AtmosphereRequest req = AtmosphereResourceImpl.class.cast(r).getRequest(false);
            try {
                if (req.getAttribute(MAX_INACTIVE) == null) {
                    logger.error("Invalid state {}", r);
                    r.removeFromAllBroadcasters();
                    config.resourcesFactory().unRegisterUuidForFindCandidate(r);
                    continue;
                }

                long l = (Long) req.getAttribute(MAX_INACTIVE);

                if (logger.isTraceEnabled() && l > 0) {
                    logger.trace("Expiring {} in {}", r.uuid(), System.currentTimeMillis() - l);
                }

                if (l > 0 && System.currentTimeMillis() - l > maxInactiveTime ) {
                    try {
                        req.setAttribute(MAX_INACTIVE, (long) -1);

                        logger.debug("IdleResourceInterceptor disconnecting {}", r);
                        Future<?> f = (Future<?>) req.getAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE);
                        if (f != null) f.cancel(false);
                        req.removeAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE);

                        WebSocket webSocket = AtmosphereResourceImpl.class.cast(r).webSocket();
                        if (webSocket != null) {
                            webSocket.close();
                        } else {
                            AsynchronousProcessor.class.cast(config.framework().getAsyncSupport()).endRequest(AtmosphereResourceImpl.class.cast(r), true);
                        }
                    } finally {
                        r.removeFromAllBroadcasters();
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
        if (maxInactiveTime > 0 && !Utils.pollableTransport(r.transport())) {
            AtmosphereResourceImpl.class.cast(r).getRequest(false).setAttribute(MAX_INACTIVE, System.currentTimeMillis());
        }
        return Action.CONTINUE;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    @Override
    public void destroy() {
        if (future != null) {
            future.cancel(true);
        }
    }

}

