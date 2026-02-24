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
package org.atmosphere.wasync.impl;

import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link Socket} implementation with Atmosphere-specific close handling.
 *
 * <p>When closing, sends an Atmosphere protocol close request to the server
 * so it can clean up resources for this client.</p>
 */
public class AtmosphereSocket extends DefaultSocket {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereSocket.class);

    public AtmosphereSocket(DefaultOptions options) {
        super(options);
    }

    @Override
    public void close() {
        // Dispatch close event before closing
        logger.debug("Closing Atmosphere socket");
        super.close();
    }
}
