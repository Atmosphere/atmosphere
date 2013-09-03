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

/**
 * An implementation of {@link AtmosphereResourceEventListener} with just log event as trace.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceEventListenerAdapter implements AtmosphereResourceEventListener {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceEventListener.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreSuspend(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSuspend(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResume(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onBroadcast(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onThrowable(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onClose(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }
}
