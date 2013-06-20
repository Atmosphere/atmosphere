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
package org.atmosphere.jersey.util;

import com.sun.jersey.spi.container.ContainerResponse;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.jersey.AtmosphereFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple util class shared among Jersey's Broadcaster.
 *
 * @author Jeanfrancois Arcand
 */
public final class JerseyBroadcasterUtil {

    private static final Logger logger = LoggerFactory.getLogger(JerseyBroadcasterUtil.class);

    public final static void broadcast(final AtmosphereResource r, final AtmosphereResourceEvent e, final Broadcaster broadcaster) {
        AtmosphereRequest request = r.getRequest();
        ContainerResponse cr = null;

        // Make sure only one thread can play with the ContainerResponse. Threading issue can arise if there is a scheduler
        // or if ContainerResponse is associated with more than Broadcaster.
        cr = (ContainerResponse) request.getAttribute(FrameworkConfig.CONTAINER_RESPONSE);

        if (cr == null || !r.isSuspended()) {
            if (cr == null) {
                logger.warn("Unexpected state. ContainerResponse has been resumed. Caching message {} for {}",
                        e.getMessage(), r.uuid());
            } else {
                logger.warn("The AtmosphereResource {} hasn't been suspended yet.",
                        r.uuid(), e);
            }

            if (DefaultBroadcaster.class.isAssignableFrom(broadcaster.getClass())) {
                DefaultBroadcaster.class.cast(broadcaster).cacheLostMessage(r, true);
            }
            AtmosphereResourceImpl.class.cast(r)._destroy();
            return;
        }

        synchronized (cr) {
            try {
                // This is required when you change the response's type
                String m = null;

                if (request.getAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE) != null) {
                    m = (String) request.getAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE);
                }

                if (m == null || m.equalsIgnoreCase("text/event-stream")) {
                    if (cr.getHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE) != null) {
                        m = cr.getHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE).toString();
                    }

                    if (m == null || m.equalsIgnoreCase("application/octet-stream")) {
                        m = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.SSE_CONTENT_TYPE);
                        if (m == null) {
                            m = "text/plain";
                        }
                    }
                }

                if (e.getMessage() instanceof Response) {
                    cr.setResponse((Response) e.getMessage());
                    cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                    cr.write();
                    try {
                        cr.getOutputStream().flush();
                    } catch (IOException ex) {
                        logger.trace("", ex);
                    }
                } else if (e.getMessage() instanceof List) {
                    for (Object msg : (List<Object>) e.getMessage()) {
                        cr.setResponse(Response.ok(msg).build());
                        cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                        cr.write();
                    }

                    // https://github.com/Atmosphere/atmosphere/issues/169
                    try {
                        cr.getOutputStream().flush();
                    } catch (IOException ex) {
                        logger.trace("", ex);
                    }
                } else {
                    if (e.getMessage() == null) {
                        logger.warn("Broadcasted message is null");
                        return;
                    }

                    cr.setResponse(Response.ok(e.getMessage()).build());
                    cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                    cr.write();
                    try {
                        cr.getOutputStream().flush();
                    } catch (IOException ex) {
                        logger.trace("", ex);
                    }
                }
            } catch (Throwable t) {
                boolean notifyAndCache = true;
                logger.trace("Unexpected exception for AtmosphereResource {} and Broadcaster {}", r.uuid(), broadcaster.getID());
                for (StackTraceElement element : t.getStackTrace()) {
                    if (element.getClassName().equals("java.io.BufferedWriter")
                            && element.getMethodName().equals("flush")) {
                        logger.trace("Workaround issue https://github.com/Atmosphere/atmosphere/issues/710");
                        notifyAndCache = false;
                    }
                }

                if (DefaultBroadcaster.class.isAssignableFrom(broadcaster.getClass())) {
                    DefaultBroadcaster.class.cast(broadcaster).onException(t, r, notifyAndCache);
                } else {
                    onException(t, r);
                }
            } finally {
                if (cr != null) {
                    cr.setEntity(null);
                }

                Boolean resumeOnBroadcast = (Boolean) request.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
                if (resumeOnBroadcast != null && resumeOnBroadcast) {

                    String uuid = (String) request.getAttribute(AtmosphereFilter.RESUME_UUID);
                    if (uuid != null) {
                        if (request.getAttribute(AtmosphereFilter.RESUME_CANDIDATES) != null) {
                            ((ConcurrentHashMap<String, AtmosphereResource>) request.getAttribute(AtmosphereFilter.RESUME_CANDIDATES)).remove(uuid);
                        }
                    }
                    r.getRequest().setAttribute(FrameworkConfig.CONTAINER_RESPONSE, null);
                    r.resume();
                }

            }
        }
    }

    final static void onException(Throwable t, AtmosphereResource r) {
        logger.trace("onException()", t);
        r.notifyListeners(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, true, false));
        AtmosphereResourceImpl.class.cast(r)._destroy();
    }
}
