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
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link BroadcasterListener}.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterListenerAdapter implements BroadcasterListener {

    private final Logger logger = LoggerFactory.getLogger(BroadcasterListenerAdapter.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPostCreate(Broadcaster b) {
        logger.trace("onPostCreate {}", b.getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onComplete(Broadcaster b) {
        logger.trace("onComplete {}", b.getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreDestroy(Broadcaster b) {
        logger.trace("onPreDestroy {}", b.getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
        logger.trace("onAddAtmosphereResource {}", b.getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
        logger.trace("onRemoveAtmosphereResource {}", b.getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMessage(Broadcaster b, Deliver deliver) {
        logger.trace("onMessage for broadcaster {} for {}", b.getID(), deliver);
    }

}
