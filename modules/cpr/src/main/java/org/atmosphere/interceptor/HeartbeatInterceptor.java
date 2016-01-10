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
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceHeartbeatEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.HeartbeatAtmosphereResourceEvent;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS;
import static org.atmosphere.cpr.ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS;
import static org.atmosphere.cpr.ApplicationConfig.HEARTBEAT_PADDING_CHAR;
import static org.atmosphere.cpr.ApplicationConfig.RESUME_ON_HEARTBEAT;

/**
 * <p>
 * An interceptor that send whitespace every in 60 seconds by default. Another value could be specified with the
 * {@link org.atmosphere.cpr.ApplicationConfig#HEARTBEAT_INTERVAL_IN_SECONDS} in the atmosphere config. The heartbeat will be scheduled as soon as the
 * request is suspended.
 * </p>
 * <p/>
 * <p>
 * Moreover, any client can ask for a particular value with the {@link HeaderConfig#X_HEARTBEAT_SERVER} header set in
 * request. This value will be taken in consideration if it is greater than the configured value. Client can also
 * specify the value "0" to disable heartbeat.
 * </p>
 * <p/>
 * <p>
 * Finally the server notifies thanks to the {@link JavaScriptProtocol} the desired heartbeat interval that the client
 * should applies. This interceptor just manage the configured value and the {@link JavaScriptProtocol protocol} sends
 * the value to the client.
 * </p>
 *
 * @author Jeanfrancois Arcand
 */
public class HeartbeatInterceptor extends AtmosphereInterceptorAdapter {
    public final static String INTERCEPTOR_ADDED = HeartbeatInterceptor.class.getName();
    public final static String HEARTBEAT_FUTURE = "heartbeat.future";

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatInterceptor.class);
    private ScheduledExecutorService heartBeat;
    private byte[] paddingBytes = "X".getBytes();
    private boolean resumeOnHeartbeat;
    private int heartbeatFrequencyInSeconds = 60;
    private AtmosphereConfig config;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * Heartbeat from client disabled by default.
     */
    private int clientHeartbeatFrequencyInSeconds;

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

    public boolean resumeOnHeartbeat() {
        return resumeOnHeartbeat;
    }

    public HeartbeatInterceptor resumeOnHeartbeat(boolean resumeOnHeartbeat) {
        this.resumeOnHeartbeat = resumeOnHeartbeat;
        return this;
    }

    @Override
    public void configure(final AtmosphereConfig config) {
        // Server
        String s = config.getInitParameter(HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            heartbeatFrequencyInSeconds = Integer.valueOf(s);
        }

        // Server
        s = config.getInitParameter(HEARTBEAT_PADDING_CHAR);
        if (s != null) {
            try {
                paddingBytes = s.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        }

        // Client
        s = config.getInitParameter(CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            clientHeartbeatFrequencyInSeconds = Integer.valueOf(s);
        }

        heartBeat = ExecutorsFactory.getScheduler(config);

        resumeOnHeartbeat = config.getInitParameter(RESUME_ON_HEARTBEAT, true);
        logger.info("HeartbeatInterceptor configured with padding value '{}', client frequency {} seconds and server frequency {} seconds", new String[]
                {new String(paddingBytes), String.valueOf(heartbeatFrequencyInSeconds), String.valueOf(clientHeartbeatFrequencyInSeconds)});

        this.config = config;
    }

    private static class Clock extends AtmosphereResourceEventListenerAdapter implements AllowInterceptor {
        public Clock() {
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResourceImpl impl = AtmosphereResourceImpl.class.cast(r);
        final AtmosphereRequest request = impl.getRequest(false);
        final AtmosphereResponse response = impl.getResponse(false);

        // Check heartbeat
        if (clientHeartbeatFrequencyInSeconds > 0) {
            byte[] body = new byte[0];
            try {
                if (!request.getMethod().equalsIgnoreCase("GET")) {
                    body = IOUtils.readEntirelyAsByte(r);
                }
            } catch (IOException e) {
                logger.warn("", e);
                cancelF(request);
                return Action.CONTINUE;
            }

            if (Arrays.equals(paddingBytes, body)) {
                // Dispatch an event to notify that a heartbeat has been intercepted
                // TODO: see https://github.com/Atmosphere/atmosphere/issues/1561
                final AtmosphereResourceEvent event = new HeartbeatAtmosphereResourceEvent(AtmosphereResourceImpl.class.cast(r));

                if (AtmosphereResourceHeartbeatEventListener.class.isAssignableFrom(r.getAtmosphereHandler().getClass())) {
                    r.addEventListener(new AtmosphereResourceEventListenerAdapter.OnHeartbeat() {
                        @Override
                        public void onHeartbeat(AtmosphereResourceEvent event) {
                            AtmosphereResourceHeartbeatEventListener.class.cast(r.getAtmosphereHandler()).onHeartbeat(event);
                        }
                    });
                }

                // Fire event
                r.notifyListeners(event);

                return Action.CANCELLED;
            }

            request.body(body);
        }

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        final int interval = extractHeartbeatInterval(impl);

        if (interval != 0) {
            if (!Utils.pollableTransport(r.transport())) {
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

            final AsyncIOWriter writer = response.getAsyncIOWriter();

            if (!Utils.resumableTransport(r.transport())
                    && AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())
                    && request.getAttribute(INTERCEPTOR_ADDED) == null) {
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
                request.setAttribute(INTERCEPTOR_ADDED, Boolean.TRUE);
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
    protected int extractHeartbeatInterval(final AtmosphereResourceImpl resource) {
        // Extract the desired heartbeat interval
        // Won't be applied if lower config value
        int interval = heartbeatFrequencyInSeconds;
        final String s = resource.getRequest(false).getHeader(HeaderConfig.X_HEARTBEAT_SERVER);

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
     * @param r        the resource
     * @param request  the request response
     * @param response the resource response
     * @return this
     */
    public HeartbeatInterceptor clock(final int interval,
                                      final AtmosphereResource r,
                                      final AtmosphereRequest request,
                                      final AtmosphereResponse response) {

        try {
            request.setAttribute(HEARTBEAT_FUTURE, heartBeat.schedule(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    synchronized (r) {
                        if (AtmosphereResourceImpl.class.cast(r).isInScope() && r.isSuspended()) {
                            try {
                                logger.trace("Heartbeat for Resource {}", r);
                                response.write(paddingBytes, false);
                                if (Utils.resumableTransport(r.transport()) && resumeOnHeartbeat) {
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
                    }
                    return null;
                }
            }, interval, TimeUnit.SECONDS));
        } catch (Throwable t) {
            logger.warn("", t);
        }

        return this;
    }

    @Override
    public String toString() {
        return "Heartbeat Interceptor Support";
    }

    @Override
    public void destroy() {
        if (destroyed.getAndSet(true)) return;

        for (AtmosphereResource r : config.resourcesFactory().findAll()) {
            cancelF(r.getRequest());
        }
    }

}
