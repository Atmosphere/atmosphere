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
 * An implementation of {@link AtmosphereResourceEventListener} which just log events with log level TRACE.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceEventListenerAdapter implements AtmosphereResourceEventListener {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceEventListenerAdapter.class);

    @Override
    public void onPreSuspend(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onSuspend(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onResume(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onHeartbeat(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onBroadcast(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onThrowable(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onClose(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    /**
     * On Heartbeat's Listener
     */
    abstract static public class OnHeartbeat extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onHeartbeat(AtmosphereResourceEvent event);
    }

    /**
     * On Suspend's Listener
     */
    abstract static public class OnSuspend extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onSuspend(AtmosphereResourceEvent event);
    }

    /**
     * On PreSuspend's Listener
     */
    abstract static public class OnPreSuspend extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onPreSuspend(AtmosphereResourceEvent event);
    }

    /**
     * On Resume's Listener
     */
    abstract static public class OnResume extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onResume(AtmosphereResourceEvent event);
    }

    /**
     * On Disconnect's Listener
     */
    abstract static public class OnDisconnect extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onDisconnect(AtmosphereResourceEvent event);
    }

    /**
     * On Broadcast's Listener
     */
    abstract static public class OnBroadcast extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onBroadcast(AtmosphereResourceEvent event);
    }

    /**
     * On Throwable's Listener
     */
    abstract static public class OnThrowable extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onThrowable(AtmosphereResourceEvent event);
    }

    /**
     * On Close's Listener
     */
    abstract static public class OnClose extends AtmosphereResourceEventListenerAdapter {
        @Override
        abstract public void onClose(AtmosphereResourceEvent event);
    }
}
