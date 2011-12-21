/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://jersey.dev.java.net/CDDL+GPL.html
 * or jersey/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at jersey/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.jersey;

import com.sun.jersey.spi.StringReader;
import com.sun.jersey.spi.StringReaderProvider;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRACKING_ID;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_RESOURCE;

/**
 * Placeholder for injection of Atmosphere object based on
 * any parameter value (header, cookie, query, matrix or path)
 *
 * @author Paul.Sandoz@Sun.Com
 * @author Jean-Francois Arcand
 */
public class AtmosphereProviders {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereProviders.class);

    public static class BroadcasterProvider implements StringReaderProvider {

        @Context
        HttpServletRequest req;

        @Override
        public StringReader getStringReader(Class type, Type genericType, Annotation[] annotations) {

            if (Broadcaster.class.isAssignableFrom(type)) {
                return new BroadcasterStringReader();
            }

            return null;
        }

        @StringReader.ValidateDefaultValue(false)
        public class BroadcasterStringReader implements StringReader {
            @Override
            public Object fromString(String topic) {
                Broadcaster broadcaster;
                try {
                    AtmosphereResource<HttpServletRequest, HttpServletResponse> r =
                            (AtmosphereResource<HttpServletRequest, HttpServletResponse>)
                                    req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                    BroadcasterFactory bp = (BroadcasterFactory)
                            req.getAttribute(ApplicationConfig.BROADCASTER_FACTORY);

                    Class<? extends Broadcaster> c;
                    try {
                        c = (Class<Broadcaster>) Class.forName((String) req.getAttribute(ApplicationConfig.BROADCASTER_CLASS));
                    } catch (Throwable e) {
                        throw new IllegalStateException(e.getMessage());
                    }
                    broadcaster = bp.lookup(c, topic, true);
                } catch (Throwable ex) {
                    throw new WebApplicationException(ex);
                }
                logger.trace("Injected Broadcaster {}", broadcaster);
                req.setAttribute(AtmosphereFilter.INJECTED_BROADCASTER, broadcaster);
                return broadcaster;
            }
        }
    }

    public static class TrackableResourceProvider implements StringReaderProvider {

        @Context
        HttpServletRequest req;

        @Override
        public StringReader getStringReader(Class type, Type genericType, Annotation[] annotations) {

            if (TrackableResource.class.isAssignableFrom(type)) {
                return new TrackableResourceStringReader();
            }

            return null;
        }

        @StringReader.ValidateDefaultValue(false)
        public class TrackableResourceStringReader implements StringReader {
            @Override
            public Object fromString(String topic) {
                TrackableResource<AtmosphereResourceImpl> trackableResource = null;
                try {
                    String trackingId = req.getHeader(X_ATMOSPHERE_TRACKING_ID);
                    if (trackingId == null) {
                        trackingId = (String) req.getAttribute(X_ATMOSPHERE_TRACKING_ID);
                    }

                    if (trackingId != null) {
                        trackableResource = (TrackableResource<AtmosphereResourceImpl>) TrackableSession.getDefault().lookup(trackingId);

                        if (req.getAttribute(ApplicationConfig.SUPPORT_TRACKABLE) != null) {
                            AtmosphereResource<?, ?> r = (AtmosphereResource<?, ?>) req.getAttribute(ATMOSPHERE_RESOURCE);
                            if (trackableResource == null) {
                                trackableResource = new TrackableResource<AtmosphereResourceImpl>(AtmosphereResourceImpl.class, trackingId, "");
                                trackableResource.setResource(r);
                            }
                            logger.trace("Tracking resource of AtmosphereResource {} with {}", r.hashCode(), trackingId);
                        }
                        req.setAttribute(AtmosphereFilter.INJECTED_TRACKABLE, trackableResource);
                    }
                } catch (Throwable ex) {
                    throw new WebApplicationException(ex);
                }
                return trackableResource;
            }
        }
    }
}
