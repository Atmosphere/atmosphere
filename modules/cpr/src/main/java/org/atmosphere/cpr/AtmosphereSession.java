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
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An AtmosphereSession allow an application to keep track of the AtmosphereResource associated with a remote client.
 * For example, with the long-polling transport, a new {@link AtmosphereResource} will be created every time a
 * reconnect occurs. If an application has a reference to the {@link AtmosphereResource}, the object will become
 * out of scope, or unusable, after the reconnection. To fix this problem, you can use this class to track and invoke
 * {@link #tryAcquire()} ()} or {@link #acquire()}} in order to get the {@link AtmosphereResource}.
 * <p/>
 * AtmosphereResource are tracked using the list of associated {@link Broadcaster}, e.g you must make sure the AtmosphereResource
 * has called {@link Broadcaster#addAtmosphereResource(AtmosphereResource)} once if you want this class to work.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereSession {

    protected final Logger logger = LoggerFactory.getLogger(AtmosphereSession.class);
    protected final AtomicReference<AtmosphereResource> resource = new AtomicReference<AtmosphereResource>();
    protected String uuid;
    protected final Semaphore latch = new Semaphore(1);
    protected BroadcasterListenerAdapter broadcasterListener;
    protected Broadcaster[] relatedBroadcasters;
    protected final boolean usesLongPolling;

    public AtmosphereSession(final AtmosphereResource r, Broadcaster... broadcasters) {
        this.uuid = r.uuid();
        this.relatedBroadcasters = broadcasters;
        this.usesLongPolling = AtmosphereResource.TRANSPORT.LONG_POLLING == r.transport();
        resource.set(r);

        broadcasterListener = new BroadcasterListenerAdapter() {
            @Override
            public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                boolean found = false;
                if (r.uuid().equalsIgnoreCase(uuid)) {
                    logger.trace("AtmosphereSession tracking :  AtmosphereResource {} added", uuid);
                    resource.set(r);
                    found = true;
                }
                if (found && latch.availablePermits() == 0) latch.release();
            }

            @Override
            public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                if (r.uuid().equalsIgnoreCase(uuid)) {
                    logger.trace("AtmosphereSession tracking :  AtmosphereResource {} removed", uuid);
                    resource.set(null);
                    latch.tryAcquire();
                }
            }
        };

        for (Broadcaster b : broadcasters) {
            b.addBroadcasterListener(broadcasterListener);
        }
    }

    /**
     * Track the current {@link AtmosphereResource} with all created {@link Broadcaster}
     *
     * @param resource an {@link AtmosphereResource}
     * @return this
     */
    public AtmosphereSession(AtmosphereResource resource) {
        this(resource, resource.getAtmosphereConfig().getBroadcasterFactory().lookupAll().toArray(new Broadcaster[]{}));
    }

    /**
     * Retrieve the {@link AtmosphereResource} associated with this session. If there is no {@link AtmosphereResource}
     * associated, return null.
     *
     * @return an {@link AtmosphereResource}
     */
    public AtmosphereResource acquire() {
        return resource.get();
    }

    /**
     * Retrieve the {@link AtmosphereResource} associated with this session. If there is no {@link AtmosphereResource}
     * associated, wait until the {@link AtmosphereResource} is retrieved. This method will wait 60 seconds and then return.
     *
     * @return an {@link AtmosphereResource}
     */
    public AtmosphereResource tryAcquire() throws InterruptedException {
        return tryAcquire(60);
    }

    /**
     * Retrieve the {@link AtmosphereResource} associated with this session. If there is no {@link AtmosphereResource}
     * associated, wait until the {@link AtmosphereResource} is retrieved.
     *
     * <p>If the resource uses long polling as its transport, this method treats the resource as a single use connection
     * and will make subsequent callers wait until the client reconnects and the {@link #broadcasterListener}'s
     * {@link BroadcasterListenerAdapter#onAddAtmosphereResource} method gets called again.</p>
     *
     * <p>WARNING: Use this method with long polling only if you intend to broadcast to the returned resource. If no broadcast is made,
     * the client won't have to reconnect, the resource won't get re-added, and any subsequent calls will have to wait until the timeout is reached.</p>
     *
     * @param timeInSecond The timeToWait before continuing the execution
     * @return an {@link AtmosphereResource} or {@code null} if the resource was not set and it didn't get set during the timeout
     */
    public AtmosphereResource tryAcquire(int timeInSecond) throws InterruptedException {
        if (usesLongPolling || resource.get() == null) {
            latch.tryAcquire(timeInSecond, TimeUnit.SECONDS);
        }
        return resource.get();
    }

    public void close() {
        for(Broadcaster br : relatedBroadcasters) {
            br.removeBroadcasterListener(broadcasterListener);
        }
    }

    public String uuid() {
        return uuid;
    }
}
