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

import org.atmosphere.HeartbeatAtmosphereResourceEvent;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * An interceptor that send whitespace every in 60 seconds by default. Another value could be specified with the
 * {@link #HEARTBEAT_INTERVAL_IN_SECONDS} in the atmosphere config. The heartbeat will be scheduled as soon as the
 * request is suspended.
 * </p>
 *
 * <p>
 * Moreover, any client can ask for a particular value with the {@link HeaderConfig#X_HEARTBEAT_SERVER} header set in
 * request. This value will be taken in consideration if it is greater than the configured value. Client can also
 * specify the value "0" to disable heartbeat.
 * </p>
 *
 * <p>
 * Finally the server notifies thanks to the {@link JavaScriptProtocol} the desired heartbeat interval that the client
 * should applies. This interceptor just manage the configured value and the {@link JavaScriptProtocol protocol} sends
 * the value to the client.
 * </p>
 *
 * @author Jeanfrancois Arcand
 */
public class HeartbeatInterceptor extends AtmosphereInterceptorAdapter {

    public final static String HEARTBEAT_INTERVAL_IN_SECONDS = HeartbeatInterceptor.class.getName() + ".heartbeatFrequencyInSeconds";

    /**
     * Configuration key for client heartbeat.
     */
    public final static String CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS = HeartbeatInterceptor.class.getName() + ".clientHeartbeatFrequencyInSeconds";
    public final static String INTERCEPTOR_ADDED = HeartbeatInterceptor.class.getName();
    public final static String HEARTBEAT_FUTURE = "heartbeat.future";

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatInterceptor.class);
    private ScheduledExecutorService heartBeat;
    private byte[] paddingBytes = " ".getBytes();

    private int heartbeatFrequencyInSeconds = 60;

    /**
     * Heartbeat from client disabled by default.
     */
    private int clientHeartbeatFrequencyInSeconds = 0;

    public HeartbeatInterceptor paddingText(byte[] paddingBytes) {
        this.paddingBytes = paddingBytes;
        return this;
    }

    /**
     * <p>
     * Gets the bytes to use when sending an heartbeat for both client and server.
     * </p>
     *
     * @return the heartbeat value
     */
    public byte[] getPaddingBytes() {
        return this.paddingBytes;
    }

    public HeartbeatInterceptor heartbeatFrequencyInSeconds(int heartbeatFrequencyInSeconds) {
        this.heartbeatFrequencyInSeconds = heartbeatFrequencyInSeconds;

        return this;
    }

    public int heartbeatFrequencyInSeconds() {
        return heartbeatFrequencyInSeconds;
    }

    /**
     * <p>
     * Gets the desired heartbeat frequency from client.
     * </p>
     *
     * @return the frequency in seconds
     */
    public int clientHeartbeatFrequencyInSeconds() {
        return clientHeartbeatFrequencyInSeconds;
    }

    public HeartbeatInterceptor clientHeartbeatFrequencyInSeconds(int clientHeartbeatFrequencyInSeconds) {
        this.clientHeartbeatFrequencyInSeconds = clientHeartbeatFrequencyInSeconds;
        return this;
    }

    @Override
    public void configure(final AtmosphereConfig config) {
        // Server
        String s = config.getInitParameter(HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            heartbeatFrequencyInSeconds = Integer.valueOf(s);
        }

        // Client
        s = config.getInitParameter(CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            clientHeartbeatFrequencyInSeconds = Integer.valueOf(s);
        }

        heartBeat = ExecutorsFactory.getScheduler(config);
    }

    private static class Clock extends AtmosphereResourceEventListenerAdapter implements AllowInterceptor {
        public Clock() {
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final int interval = extractHeartbeatInterval(r);

        if (interval != 0) {
            final AtmosphereResponse response = r.getResponse();
            final AtmosphereRequest request = r.getRequest();

            if (!Utils.pollableTransport(r.transport())){
                super.inspect(r);
                final boolean wasSuspended = r.isSuspended();

                // Suspended? Ok can clock now
                // Otherwise, the listener will do the job
                if (wasSuspended) {
                    clock(interval, r, request, response);
                }

                r.addEventListener(new Clock() {
                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {

                        // We did not clocked when this listener was added because connection was not already suspended
                        if (!wasSuspended) {
                            clock(interval, r, request, response);
                        }
                    }

                    @Override
                    public void onResume(AtmosphereResourceEvent event) {
                        cancelF(request);
                    }

                    @Override
                    public void onDisconnect(AtmosphereResourceEvent event) {
                        cancelF(request);
                    }

                    @Override
                    public void onClose(AtmosphereResourceEvent event) {
                        cancelF(request);
                    }
                });
            } else {
                return Action.CONTINUE;
            }

            AsyncIOWriter writer = response.getAsyncIOWriter();

            if (!Utils.resumableTransport(r.transport())
                    && AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())
                    && r.getRequest().getAttribute(INTERCEPTOR_ADDED) == null) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    @Override
                    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                        cancelF(request);
                        return responseDraft;
                    }

                    @Override
                    public void postPayload(final AtmosphereResponse response, byte[] data, int offset, int length) {
                        logger.trace("Scheduling heartbeat for {}", r.uuid());
                        clock(interval, r, request, response);
                    }
                });
                r.getRequest().setAttribute(INTERCEPTOR_ADDED, Boolean.TRUE);
            } else {
                byte[] body = IOUtils.readEntirelyAsByte(r);

                if (Arrays.equals(paddingBytes, body)) {
                    // Dispatch an event to notify that a heartbeat has been intercepted
                    // TODO: see https://github.com/Atmosphere/atmosphere/issues/1561
                    final AtmosphereResourceEvent event = new HeartbeatAtmosphereResourceEvent(AtmosphereResourceImpl.class.cast(r));

                    // Currently we fire heartbeat notification only for managed handler
                    if (r.getAtmosphereHandler().getClass().isAssignableFrom(ManagedAtmosphereHandler.class)) {
                        r.addEventListener(new AtmosphereResourceEventListenerAdapter.OnHeartbeat() {
                            @Override
                            public void onHeartbeat(AtmosphereResourceEvent event) {
                                ManagedAtmosphereHandler.class.cast(r.getAtmosphereHandler()).onHeartbeat(event);
                            }
                        });
                    }

                    // Fire event
                    r.notifyListeners(event);

                    return Action.CANCELLED;
                }

                request.body(body);
            }
        }

        return Action.CONTINUE;
    }

    /**
     * <p>
     * Extracts the heartbeat interval as explained in class description. This method could be overridden to change the
     * the configuration points.
     * </p>
     *
     * @param resource the resource
     * @return the interval, 0 won't trigger the heartbeat
     */
    protected int extractHeartbeatInterval(final AtmosphereResource resource) {
        // Extract the desired heartbeat interval
        // Won't be applied if lower config value
        int interval = heartbeatFrequencyInSeconds;
        final String s = resource.getRequest().getHeader(HeaderConfig.X_HEARTBEAT_SERVER);

        if (s != null) {
            try {
                interval = Integer.parseInt(s);

                if (interval != 0 && interval < heartbeatFrequencyInSeconds) {
                    interval = heartbeatFrequencyInSeconds;
                }
            } catch (NumberFormatException nfe) {
                logger.warn("{} header is not an integer", HeaderConfig.X_HEARTBEAT_SERVER, nfe);
            }
        }

        return interval;
    }

    void cancelF(AtmosphereRequest request) {
        try {
            Future<?> f = (Future<?>) request.getAttribute(HEARTBEAT_FUTURE);
            if (f != null) f.cancel(false);
            request.removeAttribute(HEARTBEAT_FUTURE);
        } catch (Exception ex) {
            // https://github.com/Atmosphere/atmosphere/issues/1503
            logger.trace("", ex);
        }
    }

    /**
     * <p>
     * Configures the heartbeat sent by the server in an interval in seconds specified in parameter for the given
     * resource.
     * </p>
     *
     * @param interval the interval in seconds
     * @param r the resource
     * @param request the request response
     * @param response the resource response
     * @return this
     */
    public HeartbeatInterceptor clock(final int interval,
                                      final AtmosphereResource r,
                                      final AtmosphereRequest request,
                                      final AtmosphereResponse response) {
        request.setAttribute(HEARTBEAT_FUTURE, heartBeat.schedule(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                if (AtmosphereResourceImpl.class.cast(r).isInScope() && r.isSuspended()) {
                    try {
                        logger.trace("Heartbeat for Resource {}", r);
                        response.write(paddingBytes, false);
                        if (Utils.resumableTransport(r.transport())) {
                            r.resume();
                        } else {
                            response.flushBuffer();
                        }
                    } catch (Throwable t) {
                        logger.trace("{}", r.uuid(), t);
                        cancelF(request);
                    }
                } else {
                    cancelF(request);
                }
                return null;
            }
        }, interval, TimeUnit.SECONDS));

        return this;
    }

    @Override
    public String toString() {
        return "Heartbeat Interceptor Support";
    }
}
