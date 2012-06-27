/*
 * Copyright 2012 Jean-Francois Arcand
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
package org.atmosphere.cpr;

import org.atmosphere.util.uri.UriTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Broadcast events to all or a subset of available {@link Broadcaster} based on their{@link org.atmosphere.cpr.Broadcaster#getID()} value.
 * This class allow broadcasting events to a set of broadcaster that maps some String like:
 * <blockquote><pre>
 *        // Broadcast the event to all Broadcaster ID starting with /hello
 *        broadcast("/hello", event)
 *        // Broadcast the event to all Broadcaster ID
 *        broaccast("/*", event);
 * </pre></blockquote>
 * The rule used is similar to path/uri mapping used by technology like Servlet, Jersey, etc.
 *
 * @author Jeanfrancois Arcand
 */
public class MetaBroadcaster {
    public static final String MAPPING_REGEX = "[/a-zA-Z0-9-&.*=;\\?]+";

    private static final Logger logger = LoggerFactory.getLogger(MetaBroadcaster.class);
    private final static MetaBroadcaster metaBroadcaster = new MetaBroadcaster();

    protected List<Broadcaster> broadcast(String path, Object message) {
        if (BroadcasterFactory.getDefault() != null) {
            Collection<Broadcaster> c = BroadcasterFactory.getDefault().lookupAll();

            final Map<String, String> m = new HashMap<String, String>();
            List<Broadcaster> l = new ArrayList<Broadcaster>();
            logger.debug("Map {}", path);
            UriTemplate t = new UriTemplate(path);
            for (Broadcaster b : c) {
                logger.debug("Trying to map {} to {}", t, b.getID());
                if (t.match(b.getID(), m)) {
                    b.broadcast(message);
                    l.add(b);
                }
                m.clear();
            }
            return l;
        } else {
            return Collections.<Broadcaster>emptyList();
        }
    }

    protected List<Broadcaster> map(String path, Object message) {

        if (path == null || path.isEmpty()) {
            throw new NullPointerException();
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.contains("*")) {
            path = path.replace("*", MAPPING_REGEX);
        }

        if (path.equals("/")) {
            path += MAPPING_REGEX;
        }

        return broadcast(path, message);
    }

    /**
     * Broadcast the message to all Broadcaster whose {@link org.atmosphere.cpr.Broadcaster#getID()} maps the broadcasterID value.
     *
     * @param broadcasterID a String (or path) that can potentially match a {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param message       a message to be broadcasted
     */
    public List<Broadcaster> broadcastTo(String broadcasterID, Object message) {
        return map(broadcasterID, message);
    }

    public final static MetaBroadcaster getDefault() {
        return metaBroadcaster;
    }

}
