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
/*
 * Copyright 2013 Kagilum - Vincent Barrier - vbarrier@kagilum.com
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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * An implementation of {@link DefaultBroadcaster} that exclude one or more {@link AtmosphereResource}
 *
 * @author Jeanfrancois Arcand
 */
public class ExcludeSessionBroadcaster
        extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(ExcludeSessionBroadcaster.class);

    public ExcludeSessionBroadcaster(String id, AtmosphereConfig config) {
        super(id, config);
    }

    /**
     * the AtmosphereResource r will be exclude for this broadcast
     *
     * @param msg
     * @param r
     * @return
     */
    @Override
    public Future<Object> broadcast(Object msg, AtmosphereResource r) {

        if (destroyed.get()) {
            throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");
        }

        Set<AtmosphereResource> sub = new HashSet<AtmosphereResource>();
        sub.addAll(resources);
        sub.remove(r);
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return null;
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, sub.size(),  this);
        dispatchMessages(new Entry(newMsg, sub, f, msg));
        return f;
    }


    /**
     * the AtmosphereResources subset will be exclude for this broadcast
     *
     * @param msg
     * @param subset
     * @return
     */
    @Override
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource> subset) {

        if (destroyed.get()) {
            return futureDone(msg);
        }

        subset.retainAll(resources);
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return futureDone(msg);
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, subset.size(), this);
        dispatchMessages(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * a list of sessions will be exclude for this broadcast
     *
     * @param msg
     * @param sessions
     * @return
     */
    public Future<Object> broadcast(Object msg, List<HttpSession> sessions) {

        if (destroyed.get()) {
            return futureDone(msg);
        }

        Set<AtmosphereResource> subset = new HashSet<AtmosphereResource>();
        subset.addAll(resources);
        for (AtmosphereResource r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    sessions.contains(r.getRequest().getSession())) {
                subset.remove(r);
            }
        }
        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return futureDone(msg);
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, subset.size(), this);
        dispatchMessages(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * session will be exclude for this broadcast
     *
     * @param msg
     * @param s
     * @return
     */
    public Future<Object> broadcast(Object msg, HttpSession s) {

        if (destroyed.get()) {
            return futureDone(msg);
        }

        Set<AtmosphereResource> subset = new HashSet<AtmosphereResource>();
        subset.addAll(resources);

        for (AtmosphereResource r : resources) {
            if (!r.getAtmosphereResourceEvent().isCancelled() &&
                    s.equals(r.getRequest().getSession())) {
                subset.remove(r);
            }
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) {
            return futureDone(msg);
        }

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg, subset.size(), this);
        dispatchMessages(new Entry(newMsg, subset, f, msg));
        return f;
    }
}