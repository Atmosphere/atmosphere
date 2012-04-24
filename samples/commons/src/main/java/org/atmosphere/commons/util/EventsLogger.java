/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.commons.util;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventsLogger implements AtmosphereResourceEventListener {

    private static final Logger logger = LoggerFactory.getLogger(EventsLogger.class);

    public EventsLogger() {
    }

    public void onSuspend(final AtmosphereResourceEvent event) {
        logger.info("onSuspend(): {}", event);
    }

    public void onResume(AtmosphereResourceEvent event) {
        logger.info("onResume(): {}", event);
    }

    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("onDisconnect(): {}", event);
    }

    public void onBroadcast(AtmosphereResourceEvent event) {
        logger.info("onBroadcast(): {}", event);
    }

    public void onThrowable(AtmosphereResourceEvent event) {
        logger.warn("onThrowable(): {}", event);
    }
}
