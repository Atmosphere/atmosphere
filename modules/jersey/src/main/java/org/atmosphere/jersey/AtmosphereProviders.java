/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.jersey;

import com.sun.jersey.spi.StringReader;
import com.sun.jersey.spi.StringReaderProvider;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

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
                    AtmosphereResource r =
                            (AtmosphereResource)
                                    req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
                    BroadcasterFactory bp = r.getAtmosphereConfig().getBroadcasterFactory();

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
}
