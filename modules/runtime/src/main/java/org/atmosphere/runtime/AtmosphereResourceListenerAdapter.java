/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.runtime;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for {@link AtmosphereResourceListener}
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceListenerAdapter implements AtmosphereResourceListener {
    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceListenerAdapter.class);

    @Override
    public void onSuspended(String uuid) {
        logger.trace("Suspended {}", uuid);
    }

    @Override
    public void onDisconnect(String uuid) {
        logger.trace("Disconnected {}", uuid);
    }
}
