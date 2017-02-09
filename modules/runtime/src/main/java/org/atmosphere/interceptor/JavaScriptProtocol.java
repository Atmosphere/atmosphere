/*
 * Copyright 2017 Async-IO.org
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

import org.atmosphere.client.TrackMessageSizeFilter;
import org.atmosphere.runtime.Action;
import org.atmosphere.runtime.ApplicationConfig;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereFramework;
import org.atmosphere.runtime.AtmosphereInterceptor;
import org.atmosphere.runtime.AtmosphereInterceptorAdapter;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResourceEvent;
import org.atmosphere.runtime.AtmosphereResourceImpl;
import org.atmosphere.runtime.AtmosphereResponse;
import org.atmosphere.runtime.BroadcastFilter;
import org.atmosphere.runtime.HeaderConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.runtime.AtmosphereResourceEventListenerAdapter.OnSuspend;
import static org.atmosphere.runtime.FrameworkConfig.CALLBACK_JAVASCRIPT_PROTOCOL;
import static org.atmosphere.runtime.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * <p>
 * An Interceptor that send back to a websocket and http client the value of {@link HeaderConfig#X_ATMOSPHERE_TRACKING_ID}.
 * </p>
 * <p/>
 * <p>
 * Moreover, if any {@link HeartbeatInterceptor} is installed, it provides the configured heartbeat interval in seconds
 * and the value to be sent for each heartbeat by the client. If not interceptor is installed, then "0" is sent to tell
 * he client to not send any heartbeat.
 * </p>
 *
 * @author Jeanfrancois Arcand
 */
public class JavaScriptProtocol extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(JavaScriptProtocol.class);
    private String wsDelimiter = "|";
    private final TrackMessageSizeFilter f = new TrackMessageSizeFilter();
    private AtmosphereFramework framework;
    private boolean enforceAtmosphereVersion = true;

    @Override
    public void configure(final AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.MESSAGE_DELIMITER);
        if (s != null) {
            wsDelimiter = s;
        }

        enforceAtmosphereVersion = Boolean.valueOf(config.getInitParameter(ApplicationConfig.ENFORCE_ATMOSPHERE_VERSION, "true"));

        framework = config.framework();
    }

    @Override
    public Action inspect(final AtmosphereResource ar) {

        if (Utils.webSocketMessage(ar)) return Action.CONTINUE;

        final AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(ar);
        final AtmosphereRequest request = r.getRequest(false);
        final AtmosphereResponse response = r.getResponse(false);

        String uuid = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        String handshakeUUID = request.getHeader(HeaderConfig.X_ATMO_PROTOCOL);
        if (uuid != null && uuid.equals("0") && handshakeUUID != null) {

            if (enforceAtmosphereVersion) {
                String javascriptVersion = request.getHeader(HeaderConfig.X_ATMOSPHERE_FRAMEWORK);
                int version = 0;
                if (javascriptVersion != null) {
                    version = parseVersion(javascriptVersion.split("-")[0]);
                }

                if (version < 221) {
                    logger.error("Invalid Atmosphere Version {}", javascriptVersion);
                    response.setStatus(501);
                    response.addHeader(X_ATMOSPHERE_ERROR, "Atmosphere Protocol version not supported.");
                    try {
                        response.flushBuffer();
                    } catch (IOException e) {
                    }
                    return Action.CANCELLED;
                }
            }

            request.header(HeaderConfig.X_ATMO_PROTOCOL, null);

            // Extract heartbeat data
            int heartbeatInterval = 0;
            String heartbeatData = "";

            for (final AtmosphereInterceptor interceptor : framework.interceptors()) {
                if (HeartbeatInterceptor.class.isAssignableFrom(interceptor.getClass())) {
                    final HeartbeatInterceptor heartbeatInterceptor = HeartbeatInterceptor.class.cast(interceptor);
                    heartbeatInterval = heartbeatInterceptor.clientHeartbeatFrequencyInSeconds() * 1000;
                    heartbeatData = new String(heartbeatInterceptor.getPaddingBytes());
                    break;
                }
            }

            String message;
            if (enforceAtmosphereVersion) {
                // UUID since 1.0.10
                message = new StringBuilder(r.uuid())
                    .append(wsDelimiter)
                    // heartbeat since 2.2
                    .append(heartbeatInterval)
                    .append(wsDelimiter)
                    .append(heartbeatData)
                    .append(wsDelimiter).toString();
            }
            else {
                // UUID since 1.0.10
                message = r.uuid();
            }

            // https://github.com/Atmosphere/atmosphere/issues/993
            final AtomicReference<String> protocolMessage = new AtomicReference<String>(message);
            if (r.getBroadcaster().getBroadcasterConfig().hasFilters()) {
                for (BroadcastFilter bf : r.getBroadcaster().getBroadcasterConfig().filters()) {
                    if (TrackMessageSizeFilter.class.isAssignableFrom(bf.getClass())) {
                        protocolMessage.set((String) f.filter(r.getBroadcaster().getID(), r, protocolMessage.get(), protocolMessage.get()).message());
                        break;
                    }
                }
            }

            if (!Utils.resumableTransport(r.transport())) {
                OnSuspend a = new OnSuspend() {
                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {
                        response.write(protocolMessage.get());
                        try {
                            response.flushBuffer();
                        } catch (IOException e) {
                            logger.trace("", e);
                        }
                        r.removeEventListener(this);
                    }
                };
                // Pass the information to Servlet Based Framework
                request.setAttribute(CALLBACK_JAVASCRIPT_PROTOCOL, a);
                r.addEventListener(a);
            } else {
                response.write(protocolMessage.get());
            }

            // We don't need to reconnect here
            if (r.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
                    || r.transport() == AtmosphereResource.TRANSPORT.STREAMING
                    || r.transport() == AtmosphereResource.TRANSPORT.SSE) {
                return Action.CONTINUE;
            } else {
                return Action.CANCELLED;
            }
        }
        return Action.CONTINUE;
    }

    private static int parseVersion(String version) {
        // Remove any qualifier if the version is 1.2.3.qualifier
        String[] parts = version.split("\\.");
        return Integer.valueOf(parts[0] + parts[1] + parts[2]);
    }

    public String wsDelimiter() {
        return wsDelimiter;
    }

    public JavaScriptProtocol wsDelimiter(String wsDelimiter) {
        this.wsDelimiter = wsDelimiter;
        return this;
    }

    public boolean enforceAtmosphereVersion(){
        return enforceAtmosphereVersion;
    }

    public JavaScriptProtocol enforceAtmosphereVersion(boolean enforceAtmosphereVersion) {
        this.enforceAtmosphereVersion = enforceAtmosphereVersion;
        return this;
    }

    @Override
    public String toString() {
        return "Atmosphere JavaScript Protocol";
    }
}
