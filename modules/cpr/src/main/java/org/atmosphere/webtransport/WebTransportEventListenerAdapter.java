/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op adapter for {@link WebTransportEventListener} that logs events at trace level.
 *
 * @author Jeanfrancois Arcand
 */
public class WebTransportEventListenerAdapter implements WebTransportEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebTransportEventListenerAdapter.class);

    @Override
    public void onPreSuspend(AtmosphereResourceEvent event) {
        logger.trace("{}", event);
    }

    @Override
    public void onOpen(WebTransportEvent<?> event) {
        logger.trace("{}", event);
    }

    @Override
    public void onMessage(WebTransportEvent<?> event) {
        logger.trace("{}", event);
    }

    @Override
    public void onClose(WebTransportEvent<?> event) {
        logger.trace("{}", event);
    }

    @Override
    public void onDisconnect(WebTransportEvent<?> event) {
        logger.trace("{}", event);
    }

    @Override
    public void onConnect(WebTransportEvent<?> event) {
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
}
