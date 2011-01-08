package org.atmosphere.jersey.util;

import com.sun.jersey.spi.container.ContainerResponse;
import org.atmosphere.cpr.AtmosphereEventLifecycle;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.jersey.AtmosphereFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
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

    public final static void broadcast(final AtmosphereResource<?,?> r, final AtmosphereResourceEvent e) {
        HttpServletRequest res = (HttpServletRequest) r.getRequest();

        try {
            ContainerResponse cr = (ContainerResponse) res.getAttribute(AtmosphereFilter.CONTAINER_RESPONSE);

            if (cr == null) {
                logger.error("Unexpected state. ContainerResponse cannot be null. The connection hasn't been suspended yet");
                return;
            }

            MediaType m = (MediaType) cr.getHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            if (e.getMessage() instanceof Response) {
                cr.setResponse((Response) e.getMessage());
                cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                cr.write();
            } else if (e.getMessage() instanceof List) {
                for (Object msg : (List<Object>) e.getMessage()) {
                    cr.setResponse(Response.ok(msg).build());
                    cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                    cr.write();
                    cr.getOutputStream().flush();
                }
            } else {
                cr.setResponse(Response.ok(e.getMessage()).build());
                cr.getHttpHeaders().add(HttpHeaders.CONTENT_TYPE, m);
                cr.write();
            }
            cr.getOutputStream().flush();
        } catch (Throwable t) {
            onException(t, r);
        } finally {
            Boolean resumeOnBroadcast = (Boolean) res.getAttribute(AtmosphereServlet.RESUME_ON_BROADCAST);
            if (resumeOnBroadcast != null && resumeOnBroadcast) {

                String uuid = (String) res.getAttribute(AtmosphereFilter.RESUME_UUID);
                if (uuid != null) {
                    if (res.getAttribute(AtmosphereFilter.RESUME_CANDIDATES) != null) {
                        ((ConcurrentHashMap<String, AtmosphereResource<?,?>>) res.getAttribute(AtmosphereFilter.RESUME_CANDIDATES)).remove(uuid);
                    }
                }
                r.resume();
            }
        }
    }

    final static void onException(Throwable t, AtmosphereResource<?,?> r) {
        try {
            logger.debug("onException()", t);

            if (t instanceof IOException && r instanceof AtmosphereEventLifecycle) {
                ((AtmosphereEventLifecycle) r).notifyListeners(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, true, false));
                ((AtmosphereEventLifecycle) r).removeEventListeners();
            }
        } finally {
            r.getBroadcaster().removeAtmosphereResource(r);
        }
    }
}
