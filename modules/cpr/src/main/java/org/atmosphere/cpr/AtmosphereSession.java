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

    private final Logger logger = LoggerFactory.getLogger(AtmosphereSession.class);
    private final AtomicReference<AtmosphereResource> resource = new AtomicReference<AtmosphereResource>();
    private String uuid;
    private final Semaphore latch = new Semaphore(1);

    public AtmosphereSession(final AtmosphereResource r, Broadcaster... broadcasters) {
        this.uuid = r.uuid();
        resource.set(r);
        for (Broadcaster b : broadcasters) {
            b.addBroadcasterListener(new BroadcasterListenerAdapter() {
                @Override
                public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                    logger.trace("", b);
                    boolean found = false;
                    if (r.uuid().equalsIgnoreCase(uuid)) {
                        resource.set(r);
                        found = true;
                    }
                    if (found && latch.availablePermits() == 0) latch.release();
                }

                @Override
                public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                    logger.trace("", b);
                    resource.set(null);
                    latch.tryAcquire();
                }
            });
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
     * @param timeInSecond The timeToWait before continuing the execution
     * @return an {@link AtmosphereResource}
     */
    public AtmosphereResource tryAcquire(int timeInSecond) throws InterruptedException {
        if (resource.get() == null) {
            latch.tryAcquire(timeInSecond, TimeUnit.SECONDS);
        }
        return resource.get();
    }
}
